# ADR 0004: Inputs numerados e reenviados até o ack

- **Status:** aceita
- **Data:** 2026-10-02

## Contexto

Os inputs são a única coisa que o jogador controla. Perder um input significa que o servidor nunca soube que você apertou a tecla naquele 1/60 s, e o seu personagem no servidor fica para trás em relação ao que você viu na tela.

Num transporte não confiável (o modo UDP do simulador), mensagens se perdem e chegam fora de ordem. Usar TCP resolveria a perda, mas traria de volta o head-of-line blocking ([ADR 0002](0002-websocket-com-simulador-de-rede.md)): um input perdido atrasaria todos os seguintes.

## Decisão

1. **Cada input tem um número de sequência** (`seq`) crescente, gerado pelo cliente.
2. **O servidor informa, em cada snapshot, o último `seq` que processou** (`ackSeq`).
3. **O cliente guarda os inputs ainda não confirmados** (a lista `pending`) e **reenvia todos eles em cada mensagem**, até 30 (0,5 s).
4. **O servidor ignora qualquer `seq` que já recebeu.** Repetição não tem efeito colateral: a operação é idempotente.

É confiabilidade construída na aplicação, sem retransmissão e sem espera: a redundância faz o papel da retransmissão.

## Como funciona em detalhe

### No cliente ([`main.js`](../../src/main/resources/static/js/main.js), `sampleInput` e `onSnapshot`)

A cada 1/60 s:

```js
const input = { seq: ++seq, buttons: currentButtons() };
pending.push(input);
socket.send(encodeInputs(pending.slice(-30)));
```

Quando chega um snapshot:

```js
pending = pending.filter((input) => input.seq > snapshot.ackSeq);
```

Linha do tempo com 150 ms de RTT:

```
t=0      envia [1]
t=16     envia [1,2]
t=33     envia [1,2,3]
...
t=150    snapshot chega com ack=3  → pending vira [4..9]
t=166    envia [4..10]
```

Com 150 ms de RTT, cada mensagem carrega uns 10 inputs (o painel mostra isso em **Inputs sem ack**). Se uma mensagem se perde, a seguinte, 16 ms depois, traz os mesmos inputs e mais um. **Uma perda só causa dano se 30 mensagens seguidas se perderem.**

### No servidor ([`Player.enqueue`](../../src/main/java/br/com/diegobraun/netcode/game/Player.java) e [`GameWorld.receiveInputs`](../../src/main/java/br/com/diegobraun/netcode/game/GameWorld.java))

```java
void enqueue(InputCommand input, int maxQueued) {
    if (input.seq() <= lastQueuedSeq) {
        return;
    }
    lastQueuedSeq = input.seq();
    queue.addLast(input);
    ...
}
```

- Os inputs de cada mensagem são ordenados por `seq` antes de enfileirar.
- Um `seq` menor ou igual ao último enfileirado é descartado: é a deduplicação.
- **Lacunas são aceitas.** Se chegar o `seq` 50 e o último era 20, o servidor aceita o 50 e os inputs 21 a 49 se perdem para sempre. Isso só acontece com mais de 0,5 s de perda contínua. A alternativa, esperar pelos que faltam, seria recriar o head-of-line blocking. A divergência resultante é corrigida pela reconciliação ([ADR 0006](0006-predicao-e-reconciliacao.md)).
- Mensagens fora de ordem também são seguras. Se o lote com 1..20 chegar depois do lote com 1..23, todos os seus `seq` já foram vistos e ele é ignorado sem efeito.

### O `ackSeq` como "você está aqui"

O `ackSeq` não serve só para limpar a lista de pendentes. Ele diz ao cliente: "a posição que eu te mandei neste snapshot é o resultado de todos os seus inputs até o `ackSeq`". É essa informação que permite a reconciliação: o cliente parte da posição do servidor e reaplica só os inputs depois do `ackSeq`.

### Custo de banda

Cada input ocupa 5 bytes. Com `N` inputs pendentes, cada mensagem tem `2 + 5N` bytes, enviada 60 vezes por segundo:

| Situação | Pendentes | Upload |
|---|---|---|
| LAN | 1–2 | ~0,7 KB/s |
| 150 ms de RTT | ~10 | ~3,5 KB/s |
| Pior caso (30 pendentes) | 30 | ~9 KB/s |

É bem pouco. A redundância é barata porque um input é minúsculo.

### Tiros pegam carona nos inputs

O tiro ([ADR 0010](0010-tiro-com-lag-compensation.md)) não tem mensagem própria: é um input com o bit `FIRE` e 16 bytes a mais. Por isso ganha de graça tudo o que está descrito acima. É reenviado até o ack, então sobrevive à perda, e é deduplicado pelo `seq`, então o mesmo tiro nunca é processado duas vezes, mesmo chegando em 10 mensagens diferentes.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| **Enviar cada input uma vez só** | Qualquer perda vira divergência entre o que você viu e o que o servidor simulou. Com 10% de perda, a tela "puxaria" o personagem várias vezes por segundo. |
| **ACK e retransmissão por input** (estilo TCP, na aplicação) | Recuperar uma perda custa pelo menos um RTT, e é preciso decidir se os inputs seguintes esperam (head-of-line blocking de novo) ou não. A redundância recupera em 16 ms, sem espera. |
| **Enviar estado em vez de inputs** ("estou em x=412") | Cliente autoritativo: trapaça trivial ([ADR 0001](0001-servidor-autoritativo-com-tick-fixo.md)). |
| **Agregar inputs** ("direita por 5 frames") | Menos bytes, mas complica a reconciliação, que precisa reaplicar exatamente os mesmos passos de 1/60 s. |

## Consequências

**Positivas**
- Perda de até 29 mensagens seguidas não tem efeito nenhum. No preset **Rede ruim (UDP)**, com 10% de perda, a correção medida foi 0 px.
- Ordem de chegada e duplicação não importam.
- Não há temporizadores nem estado de retransmissão no cliente ou no servidor.

**Negativas**
- O upload cresce linearmente com o RTT. Com 1 s de RTT, cada mensagem carregaria 30 inputs.
- Mais de 0,5 s de perda contínua perde inputs de vez. A tela é corrigida, mas o jogador "perde" aquele movimento.

## Como verificar

- Preset **Rede ruim (UDP)**: **Inputs sem ack** fica entre 13 e 17, e **Correção** em 0 px mesmo com 10% de perda.
- Testes: [`GameWorldTest`](../../src/test/java/br/com/diegobraun/netcode/game/GameWorldTest.java) (`ignoresRedundantInputsAlreadyReceived`, `acceptsGapsWhenInputsAreLost`) e [`GameWebSocketIntegrationTest`](../../src/test/java/br/com/diegobraun/netcode/net/GameWebSocketIntegrationTest.java), que reenvia o mesmo lote de 12 inputs e confirma que a posição não muda.
