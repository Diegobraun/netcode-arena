# ADR 0002: WebSocket como transporte, com simulador de UDP e TCP

- **Status:** aceita
- **Data:** 2026-10-02

## Contexto

Jogos de ação rápidos costumam usar UDP. O motivo é o *head-of-line blocking* do TCP: quando um pacote se perde, o TCP segura a entrega de todos os seguintes até retransmitir o perdido. Num jogo, o pacote perdido costuma ser um snapshot que, quando finalmente chega, já está velho.

O objetivo deste projeto é **mostrar** essa diferença e as técnicas que tornam um jogo online jogável. Isso cria dois problemas:

1. **O navegador não fala UDP.** Por segurança, uma página web não abre sockets UDP arbitrários.
2. **Em `localhost` a rede é perfeita.** Sem latência nem perda, nenhuma técnica de netcode faz diferença visível.

## Decisão

1. **O transporte real é WebSocket** (Spring WebSocket no servidor, `WebSocket` nativo no browser). Funciona em qualquer navegador, sem instalar nada, e passa por proxies e firewalls como HTTP comum.
2. **Um simulador de rede no servidor** aplica, mensagem a mensagem, latência, jitter e perda, com dois modos que imitam o que UDP e TCP fazem quando um pacote se perde. Cada conexão tem as suas próprias condições, ajustadas pelo painel.

O jogo em si não sabe que existe um simulador. Ele envia e recebe mensagens, e o simulador decide **quando** cada uma é entregue, ou **se** é entregue.

## Como funciona em detalhe

### Onde o simulador fica

Cada conexão ([`ClientConnection`](../../src/main/java/br/com/diegobraun/netcode/net/ClientConnection.java)) tem dois links simulados, um por sentido:

```
navegador ──WebSocket──► [uplink simulado]   ──► servidor processa inputs e pings
navegador ◄──WebSocket── [downlink simulado] ◄── servidor envia snapshots e pongs
```

A latência configurada vale **para cada sentido**. Com 75 ms no painel, o RTT medido fica em torno de 150 ms.

### O que acontece com cada mensagem

[`SimulatedLink.plan`](../../src/main/java/br/com/diegobraun/netcode/net/SimulatedLink.java) decide o destino de uma mensagem enviada no instante `agora`:

```
atraso = latência + aleatório(0 .. jitter)
perdida = aleatório(0 .. 100) < perda%

modo UDP:
  se perdida e não confiável → descarta
  entrega em: agora + atraso

modo TCP:
  se perdida → atraso += max(200 ms, 2 × latência)      ← retransmissão
  entrega em: max(agora + atraso, última entrega)        ← nunca ultrapassa a anterior
```

A linha marcada como "nunca ultrapassa a anterior" é o head-of-line blocking. No modo TCP, uma mensagem nunca é entregue antes da que foi enviada antes dela. Se uma se perdeu e vai chegar 200 ms atrasada, todas as seguintes esperam por ela, mesmo que tenham "chegado" antes.

No modo UDP essa regra não existe. Com jitter alto, uma mensagem enviada depois pode chegar antes. O contador **Fora de ordem** do painel mostra isso: aumente o jitter para 100 ms em UDP e ele sobe. Em TCP ele fica sempre em zero.

### A penalidade de retransmissão

O TCP real detecta a perda de duas formas:
- **Fast retransmit:** quando chegam três ACKs duplicados, ele retransmite em cerca de um RTT.
- **Timeout (RTO):** quando não há tráfego suficiente para ACKs duplicados. No Linux, o RTO mínimo é de 200 ms.

O simulador usa `max(200 ms, 2 × latência)`, ou seja, o maior valor entre o RTO mínimo e um RTT. É uma aproximação deliberadamente simples. O TCP real também tem controle de congestionamento, que reduziria a taxa de envio depois de cada perda, e isso não é simulado.

### Mensagens confiáveis no modo UDP

Jogos sobre UDP não deixam tudo sujeito a perda. Eles mantêm um canal confiável para eventos que não podem sumir. O simulador tem o mesmo conceito: `transmit(..., reliable = true)` nunca descarta a mensagem no modo UDP, só aplica a latência. O aviso de mudança de tick rate e de bots (`config`) usa esse canal. Snapshots e pongs não: se se perderem, o próximo resolve.

### Fila de entrega ordenada (e o bug que ela corrigiu)

A primeira versão agendava cada mensagem como uma tarefa independente: "envie esta daqui a X ns". Em teste no navegador, o modo TCP mostrou **snapshots fora de ordem**, algo que o TCP nunca faz.

O motivo: quando várias mensagens ficam presas atrás de uma retransmissão, todas recebem o **mesmo** instante de entrega. Entre calcular esse instante e agendar a tarefa passam alguns nanossegundos, diferentes para cada mensagem, e isso bastava para inverter a ordem de duas tarefas com o mesmo horário.

A correção (em `SimulatedLink.transmit`): cada link mantém uma fila de prioridade de mensagens em trânsito, ordenada por **(instante de entrega, número de sequência)**. As tarefas agendadas não entregam "a sua" mensagem. Elas entregam **todas as mensagens cujo instante já passou, na ordem da fila**. Assim, empates são desfeitos pela ordem de envio, e a garantia do TCP passa a valer. O teste `tcpModeDeliversInSendOrderEvenWhenMessagesShareADeliveryInstant` cobre exatamente esse caso.

### O que é real e o que é simulado

| Aspecto | Real ou simulado |
|---|---|
| Conexão entre navegador e servidor | Real: WebSocket sobre TCP em loopback, praticamente sem latência nem perda |
| Latência, jitter e perda | Simulados no servidor, por mensagem |
| Comportamento UDP (descartar, reordenar) | Simulado |
| Comportamento TCP (retransmitir, bloquear a fila) | Simulado |
| Controle de congestionamento, janelas, ACKs | Não simulados |

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| **UDP de verdade com um cliente nativo** (Java, Godot, Unity) | A demonstração deixaria de rodar no navegador com um clique. |
| **WebRTC DataChannel** em modo não confiável (`ordered: false, maxRetransmits: 0`) | É o "UDP do navegador" mais usado hoje, mas exige sinalização, ICE e STUN/TURN. O código de rede ficaria maior que o jogo, e em localhost a rede continuaria perfeita: o simulador seria necessário do mesmo jeito. |
| **WebTransport** (HTTP/3 sobre QUIC, com datagramas não confiáveis) | A opção mais limpa a médio prazo, mas o suporte em servidores Java ainda é imaturo e exige HTTPS com certificado válido até em desenvolvimento. |
| **Simular a rede no cliente, em JavaScript** | Funcionaria para o downlink, mas os timers do navegador são imprecisos (e reduzidos em abas em segundo plano), e os inputs que vão para o servidor ficariam sem simulação. |
| **Ferramentas do sistema operacional** (`tc netem` no Linux, Network Link Conditioner no macOS) | Simulam perda real de pacotes TCP, mas exigem privilégios de administrador, não permitem trocar UDP por TCP com um clique e afetam a máquina inteira. |

## Consequências

**Positivas**
- Roda em qualquer navegador e qualquer sistema, sem configuração.
- UDP e TCP podem ser comparados lado a lado, com as mesmas condições e um clique de diferença.
- Cada aba pode ter uma rede diferente, o que permite ver um jogador com rede boa observando outro com rede ruim.

**Negativas**
- Em produção, este jogo rodaria sobre TCP de verdade, com head-of-line blocking real por baixo do simulador. O modo UDP mostra o que **seria possível** com UDP, não o que o WebSocket entrega.
- O modelo de TCP é aproximado (sem controle de congestionamento nem janela).

## Como verificar

- Presets **Rede ruim (UDP)** e **Rede ruim (TCP)**: mesmas condições (100 ms, jitter 40 ms, 10% de perda). Nos testes deste projeto, o RTT ficou em ~216 ms no UDP e entre ~350 e ~400 ms no TCP, com snapshots chegando em rajadas no TCP.
- Testes: [`SimulatedLinkTest`](../../src/test/java/br/com/diegobraun/netcode/net/SimulatedLinkTest.java), que cobre latência e jitter, fração de perda no UDP, reordenação no UDP, ordem e ausência de perda no TCP, e o canal confiável.
