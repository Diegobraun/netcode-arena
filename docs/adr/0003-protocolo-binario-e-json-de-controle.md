# ADR 0003: Protocolo binário para o jogo, JSON para controle

- **Status:** aceita
- **Data:** 2026-10-02

## Contexto

Dois tipos de mensagem muito diferentes trafegam pela mesma conexão:

| Tipo | Frequência | Exemplos |
|---|---|---|
| **Tráfego de jogo** | 60 mensagens/s do cliente e 20 a 60/s do servidor, por jogador | inputs, snapshots |
| **Controle** | Raramente: no início, quando alguém mexe no painel, a cada 500 ms para medir o RTT | boas-vindas, configuração da rede simulada, tick rate, ping/pong |

O tráfego de jogo domina a banda e a CPU de serialização. O de controle é raro e precisa ser fácil de ler e evoluir.

## Decisão

- **Tráfego de jogo em binário**, com layout fixo, big-endian (o padrão do `ByteBuffer` em Java e do `DataView` em JavaScript), em frames binários do WebSocket.
- **Controle em JSON**, em frames de texto do WebSocket.

O tipo do frame (texto ou binário) já diz qual protocolo é: o cliente testa `typeof event.data === "string"`, e o servidor separa em `handleTextMessage` e `handleBinaryMessage`.

## Como funciona em detalhe

### Input (cliente → servidor), binário

Implementado em [`protocol.js`](../../src/main/resources/static/js/protocol.js) (`encodeInputs`) e [`Protocol.decodeInputs`](../../src/main/java/br/com/diegobraun/netcode/net/Protocol.java).

```
offset  tamanho  campo
0       u8       tipo = 1 (INPUTS)
1       u8       quantidade N (máx. 64; o cliente manda até 30)
2       ...      para cada input:
                   u32  seq       número de sequência
                   u8   buttons   bits: 1=cima 2=baixo 4=esquerda 8=direita 16=atirar
                   se o bit 16 (FIRE) estiver ligado, mais 16 bytes:
                   f32  aimX      ponto mirado
                   f32  aimY
                   f64  viewTick  tick que estava na tela ao atirar
```

Uma mensagem carrega **vários** inputs: todos os ainda não confirmados pelo servidor, até 30. O motivo está no [ADR 0004](0004-inputs-sequenciados-e-redundantes.md). Cada input ocupa 5 bytes, ou 21 quando carrega um tiro ([ADR 0010](0010-tiro-com-lag-compensation.md)). O servidor rejeita mensagens truncadas ou com bytes sobrando, e mascara `buttons` com `0x1F` para ignorar bits desconhecidos. O `viewTick` é `f64` porque é fracionário e cresce sem parar: com `f32`, a parte fracionária perderia precisão depois de algumas horas de partida.

### Snapshot (servidor → cliente), binário

Implementado em [`Protocol.encodeSnapshot`](../../src/main/java/br/com/diegobraun/netcode/net/Protocol.java) e `decodeSnapshot` em `protocol.js`.

```
offset  tamanho  campo
0       u8       tipo = 1 (SNAPSHOT)
1       u32      tick do servidor
5       u32      ackSeq: último input SEU que o servidor processou
9       u16      yourId: o seu id de jogador
11      u8       tickRate atual
12      u16      quantidade de jogadores P
14      P × 14   para cada jogador:
                   u16  id
                   f32  x
                   f32  y
                   u16  score
                   u8   cor (índice da paleta)
                   u8   flags (1 = bot)
...     u16      quantidade de orbes O
...     O × 10   para cada orbe:
                   u16  id
                   f32  x
                   f32  y
...     u16      quantidade de tiros T processados neste tick
...     T × 31   para cada tiro (ADR 0010):
                   u16  shooterId
                   u16  hitId (0 = errou)
                   u8   flags (1 = compensado)
                   f32  originX, originY
                   f32  endX, endY
                   f32  targetX, targetY  (onde o servidor viu o alvo)
                   u16  rewindMs
```

Tamanho: `18 + 14 × jogadores + 10 × orbes + 31 × tiros` bytes. Com você, 6 bots, 12 orbes e nenhum tiro naquele tick, são **236 bytes**. A 20 Hz, 4,7 KB/s, que é o valor exibido em **Download** no painel nessa situação.

O snapshot é montado **para cada conexão**, porque o `ackSeq` e o `yourId` são diferentes para cada jogador. O resto do conteúdo é igual para todos.

### Por que `f32` para posições

As coordenadas vão de 0 a 960. Um `float32` tem precisão de ~0,00006 px nessa faixa, muito abaixo de um pixel, e ocupa metade de um `double`. A diferença entre o valor `double` do servidor e o `float32` recebido aparece na reconciliação ([ADR 0006](0006-predicao-e-reconciliacao.md)) como uma correção de milésimos de pixel, invisível.

### Mensagens de controle (JSON)

| Direção | `type` | Conteúdo |
|---|---|---|
| servidor → cliente | `welcome` | `playerId`, `tickRate`, `bots` e `constants` (arena, raios, velocidade, `inputRate`) |
| servidor → cliente | `config` | novo `tickRate` e `bots`, quando alguém muda no painel |
| servidor → cliente | `pong` | eco do `ping`, para medir o RTT |
| cliente → servidor | `net` | `latencyMs`, `jitterMs`, `lossPercent`, `mode` (`udp` ou `tcp`) para a SUA conexão |
| cliente → servidor | `server` | `tickRate` e `bots` (valem para todos) |
| cliente → servidor | `ping` | `id` e `t` (o horário do cliente), devolvidos no `pong` |

As constantes da física vêm do servidor no `welcome`, e o cliente usa esses valores em vez de ter cópias próprias. Assim os dois lados nunca divergem nos números ([ADR 0005](0005-fisica-deterministica-compartilhada.md)).

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| **JSON para tudo** | O mesmo snapshot (7 jogadores, 12 orbes) tem ~962 bytes em JSON contra 236 em binário, ~4x mais, e parse de texto a 20–60 Hz por jogador. |
| **Protocol Buffers** | Daria payload próximo do binário manual, com evolução de esquema mais segura. Mas exige compilar o `.proto` e uma biblioteca no navegador, e o objetivo aqui é que cada byte do protocolo seja visível no código. Para um jogo real, Protobuf ou FlatBuffers seriam bons candidatos. |
| **Delta compression** (enviar só o que mudou desde o último snapshot confirmado) | É o que jogos reais fazem e reduz muito a banda, mas exige que o cliente confirme snapshots e que o servidor guarde um histórico por cliente. Está no [ADR 0009](0009-fora-do-escopo-e-proximos-passos.md). |
| **Binário também para controle** | Ganho de banda irrelevante, porque essas mensagens são raras, e perda de legibilidade ao depurar. |

## Consequências

**Positivas**
- Banda pequena e previsível: dá para calcular o tamanho de cada mensagem com uma fórmula.
- Decodificação sem alocação de strings, em poucas linhas de código nos dois lados.
- O controle continua fácil de inspecionar no DevTools do navegador.

**Negativas**
- O layout está duplicado em Java e JavaScript. Uma mudança precisa ser feita nos dois lados, na mesma ordem.
- Não há versionamento do protocolo: cliente e servidor precisam ser da mesma versão, o que aqui é garantido porque o servidor serve o próprio cliente.
- Ids em `u16` limitam a 65.535 jogadores e orbes por partida, e o tick em `u32` dá a volta depois de ~2,7 anos a 50 Hz. Os dois limites são irrelevantes aqui.

## Como verificar

- Painel: **Download** e **Upload** mostram a banda real. Mude o número de bots e veja o download crescer 14 bytes × tick rate por bot.
- DevTools → Network → WS: os frames binários aparecem com o tamanho exato.
- Testes: [`ProtocolTest`](../../src/test/java/br/com/diegobraun/netcode/net/ProtocolTest.java) (round-trip do snapshot com 10 jogadores e um evento de tiro; inputs com e sem tiro; rejeição de inputs malformados e de tiro sem os 16 bytes de dados).
