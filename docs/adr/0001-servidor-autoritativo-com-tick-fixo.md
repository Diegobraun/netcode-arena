# ADR 0001: Servidor autoritativo com tick fixo

- **Status:** aceita
- **Data:** 2026-10-02

## Contexto

Num jogo multiplayer, cada participante vê o mundo com atraso e por uma rede que pode perder ou embaralhar mensagens. Alguém precisa decidir o que é verdade: onde cada jogador está, quem pegou o orbe, qual é o placar. Se cada cliente decidir por conta própria, os mundos divergem ("no meu computador eu peguei o orbe primeiro") e trapacear fica trivial: basta o cliente dizer "estou em cima do orbe".

## Decisão

1. **O servidor é a única fonte da verdade.** Ele guarda o estado do mundo em [`GameWorld`](../../src/main/java/br/com/diegobraun/netcode/game/GameWorld.java) e só ele move jogadores, detecta coleta de orbes e soma pontos.
2. **O cliente envia intenções, não resultados.** O cliente nunca diz "estou em x=412". Ele diz "no input 1042 eu estava apertando para a direita". O servidor aplica a física e decide onde o jogador foi parar.
3. **A simulação avança em passos fixos (*ticks*).** O servidor roda a 20 Hz por padrão (configurável entre 5, 10, 20, 30 e 60 Hz no painel). Em cada tick ele:
   1. processa os inputs que chegaram de cada jogador humano;
   2. move os bots;
   3. verifica colisão com orbes e atualiza o placar;
   4. repõe orbes até haver 12 na arena;
   5. envia um snapshot para cada cliente.

## Como funciona em detalhe

### O loop

[`GameServer.scheduleLoop`](../../src/main/java/br/com/diegobraun/netcode/net/GameServer.java) agenda o método `tick()` com `scheduleAtFixedRate` num executor de uma thread só, chamada `game-loop`. O período é `1 s / tickRate`: 50 ms a 20 Hz.

`scheduleAtFixedRate` mira o ritmo, não o intervalo entre execuções. Se um tick atrasar, o próximo é disparado mais cedo para compensar. É o comportamento desejado: o número de ticks por segundo fica estável.

Trocar o tick rate no painel cancela o agendamento atual e cria outro. Todos os clientes são avisados com uma mensagem `config`.

### Duas taxas diferentes: input e tick

O cliente gera **60 inputs por segundo** (`INPUT_RATE = 60`), independentemente do tick do servidor. Cada input representa exatamente 1/60 s de movimento. A 20 Hz chegam em média 3 inputs por tick, e o servidor aplica os 3 em sequência.

Separar as duas taxas tem um motivo: o movimento do jogador não depende do tick rate. Um segundo apertando "direita" sempre move o jogador `220 px` (`SPEED`), seja o servidor a 5 Hz ou a 60 Hz. O tick rate só muda **com que frequência** o mundo é publicado, e por isso dá para experimentar 5 Hz no painel sem mudar a jogabilidade, só a suavidade.

### Limite de inputs por tick

Um cliente honesto manda 60 inputs por segundo. Um cliente trapaceiro poderia mandar 600 para andar 10 vezes mais rápido. O servidor limita quantos inputs aplica por tick a `ceil(60 / tickRate) × 3`, ou seja, 9 por tick a 20 Hz: três vezes o esperado, o bastante para absorver rajadas causadas por jitter. A fila de cada jogador também tem no máximo 60 inputs (1 s). O excedente é descartado do início da fila.

### Bots

Os bots são jogadores controlados pelo servidor, para que a arena tenha movimento mesmo com uma pessoa só. Eles usam a mesma física dos humanos (`Physics.step`), com a mesma taxa de 60 inputs por segundo. Como o tick rate pode não dividir 60 exatamente, cada bot acumula um "orçamento" fracionário de inputs por tick (`botInputBudget`).

A IA é simples: cada bot vai para o orbe mais próximo que nenhum outro bot alcança antes. Sem essa regra, todos corriam para o mesmo orbe e ficavam amontoados. Há um teste específico para isso (`botsSpreadOutInsteadOfChasingTheSameOrb`).

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| **Cliente autoritativo** (cada um envia a própria posição e o servidor só repassa) | Trapaça trivial (teletransporte, velocidade) e conflitos sem árbitro sobre quem pegou o orbe. |
| **Peer-to-peer com *lockstep*** (todos simulam tudo e trocam só inputs, avançando juntos) | Clássico de jogos de estratégia (StarCraft, Age of Empires). Exige determinismo perfeito em todos os clientes e trava todo mundo no ritmo do jogador mais lento. Não combina com entrada e saída livre de jogadores. |
| **Simulação dirigida por evento** (o servidor processa cada input assim que ele chega) | O ritmo do mundo passaria a depender da rede, e seria impossível publicar estados consistentes em intervalos regulares, que a interpolação no cliente precisa ([ADR 0007](0007-interpolacao-de-entidades-remotas.md)). |

## Consequências

**Positivas**
- Um único estado verdadeiro. Não existe "no meu computador eu peguei".
- Trapaça de movimento é limitada pela física e pelo limite de inputs por tick.
- O tick rate vira um parâmetro de qualidade: mais ticks deixam o mundo mais fresco, ao custo de mais banda e CPU.

**Negativas**
- Toda ação passa pelo servidor. Sem as técnicas dos ADRs [0006](0006-predicao-e-reconciliacao.md) e [0007](0007-interpolacao-de-entidades-remotas.md), o jogador sentiria o RTT inteiro em cada tecla.
- O servidor custa CPU proporcional a jogadores × tick rate. Aqui isso é desprezível; num jogo grande é o principal custo de infraestrutura.
- O servidor é ponto único de falha da partida.

## Como verificar

- Com o preset **150 ms RTT**, desligue **Predição no cliente**: o personagem só se move ~150 ms depois da tecla, porque quem move é o servidor.
- Mude o **Tick rate** para 5 Hz: a velocidade de movimento continua a mesma, mas tudo fica menos suave.
- Testes: [`GameWorldTest`](../../src/test/java/br/com/diegobraun/netcode/game/GameWorldTest.java) (`limitsHowManyInputsAreSimulatedPerTick`, `collectingAnOrbScoresAndRespawnsIt`, `botsSpreadOutInsteadOfChasingTheSameOrb`).
