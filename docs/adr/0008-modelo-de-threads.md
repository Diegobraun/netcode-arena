# ADR 0008: Uma thread dona do mundo, outra da rede

- **Status:** aceita
- **Data:** 2026-10-02

## Contexto

O servidor recebe eventos de várias fontes ao mesmo tempo:

- **threads do Tomcat**, uma por mensagem WebSocket recebida (inputs, pings, ajustes do painel, conexões e desconexões);
- **o relógio do jogo**, a cada tick;
- **o simulador de rede**, que entrega mensagens em instantes futuros ([ADR 0002](0002-websocket-com-simulador-de-rede.md)).

Se todas essas threads mexessem diretamente no `GameWorld`, seriam necessários locks em todo lugar, ou haveria corridas: um jogador removido no meio de um tick, um input aplicado enquanto o snapshot é montado.

Além disso, o `WebSocketSession.sendMessage` do Spring **não é thread-safe**: duas threads enviando para a mesma sessão ao mesmo tempo causam erro.

## Decisão

Duas threads com papéis exclusivos, e nenhum lock no código do jogo:

| Thread | Nome | Dona de | O que faz |
|---|---|---|---|
| Loop do jogo | `game-loop` | `GameWorld` e todo o estado do jogo | Roda os ticks e aplica tudo o que altera o mundo: novos jogadores, saídas, inputs, mudanças de tick rate e de bots |
| Rede simulada | `net-sim` | Os envios para as sessões WebSocket | Entrega as mensagens em trânsito na hora certa e faz todo `sendMessage` |
| Tomcat | várias | nada | Só decodificam a mensagem e repassam para uma das duas threads |

`GameWorld`, `Player` e `ArrayDeque` são classes comuns, **sem nenhuma sincronização**, porque só a `game-loop` toca nelas.

## Como funciona em detalhe

### Todo acesso ao mundo vira uma tarefa na `game-loop`

Em [`GameServer`](../../src/main/java/br/com/diegobraun/netcode/net/GameServer.java), cada ponto de entrada repassa o trabalho:

```java
void connect(WebSocketSession session) {
    game.execute(() -> {
        Player player = world.addPlayer(false);
        ...
    });
}

void disconnect(WebSocketSession session) {
    ...
    game.execute(() -> world.removePlayer(connection.playerId()));
}
```

`game` é um `ScheduledExecutorService` com **uma thread só**. Tarefas enviadas com `execute` e o tick agendado com `scheduleAtFixedRate` rodam em fila, nunca ao mesmo tempo. É o modelo de *event loop*: o mesmo do Node.js, do Netty e da maioria dos servidores de jogo.

### O caminho de um input

```
Tomcat (thread qualquer)
  handleBinaryMessage → Protocol.decodeInputs                 decodifica (sem estado compartilhado)
  → uplink.transmit(entrega, net-sim)                         simulador agenda a chegada

net-sim (no instante simulado de chegada)
  → game.execute(() -> world.receiveInputs(...))              repassa para o dono do mundo

game-loop
  → receiveInputs: dedupe e enfileira                         (ADR 0004)
  → no próximo tick: aplica a física
```

O tiro segue esse mesmo caminho. Ele só é resolvido dentro do tick, na `game-loop`, quando `processInputs` encontra um input com `FIRE`, e `GameWorld.fire` consulta o histórico de posições. O histórico é gravado no fim de cada tick pela mesma thread, por isso o rewind lê posições passadas sem lock e sem risco de ver um tick pela metade.

### O caminho de um snapshot

```
game-loop (no tick)
  → Protocol.encodeSnapshot                                    lê o mundo na própria thread dona
  → downlink.transmit(entrega, net-sim)

net-sim (no instante simulado de entrega)
  → session.sendMessage(...)                                   único lugar que envia
```

Como **todo** envio passa pela `net-sim`, nunca há duas threads chamando `sendMessage` na mesma sessão. Isso vale também para mensagens que não passam pelo simulador, como o `welcome`, enviado com `network.execute(...)`.

### Estado compartilhado que sobra

Pouca coisa é lida por mais de uma thread, e cada caso usa a estrutura certa:

- `connections`: `ConcurrentHashMap`, porque o Tomcat consulta e a `game-loop` itera;
- `tickRate` e `botCount`: `volatile`, porque são escritos pela `game-loop` e lidos pelas outras;
- as condições de cada link: `volatile` dentro de `SimulatedLink`, porque o painel atualiza pela thread do Tomcat;
- a fila de mensagens em trânsito de cada link: `synchronized`, porque várias threads adicionam mensagens (o Tomcat no uplink; a `game-loop` e a `net-sim` no downlink), e a `net-sim` retira.

Fica de fora dessa lista o que só a `game-loop` toca: o histórico de posições (`PositionHistory`) e os eventos de tiro do tick (`shotsThisTick`). O snapshot copia esses eventos para bytes ainda na `game-loop`, antes de entregar à `net-sim`, então nenhuma outra thread vê essas estruturas.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| **Locks no `GameWorld`** (`synchronized` ou `ReentrantLock`) | Funciona, mas todo método precisa lembrar de travar, e um tick longo segura todas as threads do Tomcat. Os erros de concorrência passam a ser possíveis em qualquer mudança futura. |
| **Estruturas concorrentes no mundo** (`ConcurrentHashMap` para jogadores, filas concorrentes) | Cada estrutura fica segura isoladamente, mas operações compostas não ("mover e depois checar colisão" precisa ver um estado consistente). |
| **Uma thread para tudo** (jogo e envios) | Simples, mas um cliente lento cujo `sendMessage` bloqueie atrasaria o tick de todo mundo. |
| **Virtual threads** | Ajudam quando há muitas tarefas bloqueantes independentes. Aqui o objetivo é o oposto: serializar tudo numa thread só. |

## Consequências

**Positivas**
- O código do jogo é sequencial e sem locks: dá para ler `GameWorld` como um programa de uma thread só.
- Não há corridas entre tick, entrada e saída de jogadores.
- Envios para a mesma sessão nunca colidem.

**Negativas**
- **Um núcleo de CPU para o jogo inteiro.** Escalar para milhares de jogadores exigiria dividir o mundo em salas ou regiões, cada uma com o seu loop ([ADR 0009](0009-fora-do-escopo-e-proximos-passos.md)).
- **Um cliente lento pode atrasar os envios para todos.** `sendMessage` bloqueia a `net-sim` até o Tomcat aceitar os bytes. Com muitos jogadores, o correto seria envolver cada sessão num `ConcurrentWebSocketSessionDecorator`, com limite de buffer e de tempo, e desconectar quem não acompanha.
- Uma exceção dentro do tick precisa ser capturada (`try/catch` em `tick()`), senão o `ScheduledExecutorService` cancela silenciosamente todas as execuções futuras.

## Como verificar

- [`GameWebSocketIntegrationTest`](../../src/test/java/br/com/diegobraun/netcode/net/GameWebSocketIntegrationTest.java) exercita o caminho completo por WebSocket: conexão, inputs, snapshots e ping.
- Abra várias abas com redes diferentes. Cada uma recebe as suas mensagens no próprio ritmo, sem interferir nas outras, porque cada conexão tem os seus próprios links simulados.
