# ADR 0007: Interpolação dos outros jogadores

- **Status:** aceita
- **Data:** 2026-10-02

## Contexto

Os outros jogadores (bots e outras abas) não podem ser previstos como o seu personagem ([ADR 0006](0006-predicao-e-reconciliacao.md)): o cliente não conhece os inputs deles. Ele só sabe onde estavam em cada snapshot.

Desenhar sempre a posição do último snapshot tem três problemas:

1. **Movimento em degraus.** A 20 Hz, a posição muda 20 vezes por segundo, enquanto a tela é desenhada 60 ou mais. A 5 Hz o personagem "teleporta" a cada 200 ms.
2. **Jitter vira tremedeira.** Se um snapshot atrasa 40 ms e o seguinte não, o movimento para e depois dá um salto.
3. **Perda vira travada.** Um snapshot perdido deixa o personagem parado por um tick inteiro.

## Decisão

Os outros jogadores são desenhados **um pouco no passado**, 100 ms por padrão (configurável no painel), **interpolando** entre os dois snapshots que cercam esse instante. A 20 Hz, 100 ms são 2 ticks: quase sempre já existe um snapshot "depois" do ponto que está sendo desenhado, e o movimento pode ser suave.

## Como funciona em detalhe

### Histórico de snapshots

Cada snapshot recebido vai para um histórico (`history`). Os mais velhos que 2 s são descartados, mantendo pelo menos dois. **Snapshots que chegam fora de ordem são ignorados por completo**: se o tick for menor ou igual ao do último recebido, ele é contado em **Fora de ordem** e descartado. Isso só acontece no modo UDP com jitter alto.

### Relógio de renderização (`advanceRenderTick`)

A interpolação precisa de um "relógio" que diga qual tick desenhar agora, `renderTick`, um número fracionário. Ele é derivado do servidor, não do relógio do navegador:

```js
const delayTicks = (settings.interpDelayMs / 1000) * tickRate;                // 100 ms a 20 Hz → 2 ticks
const serverTickNow = latest.tick + ((now - latestReceivedAt) / 1000) * tickRate;
const target = serverTickNow - delayTicks;
```

- `serverTickNow` estima o tick atual do servidor: o último recebido mais o tempo decorrido desde então.
- `target` é esse valor menos o atraso de interpolação.

A cada quadro, `renderTick` avança no ritmo do tempo real e é **puxado devagar** em direção ao `target`:

```js
renderTick += (elapsedMs / 1000) * tickRate;
renderTick += (target - renderTick) * 0.05;
```

O fator de 5% por quadro é o que absorve o jitter. Um snapshot que chega 40 ms atrasado desloca o `target`, mas o relógio de renderização só se ajusta aos poucos, em vez de dar um salto. Se a diferença passar de 1 s (por exemplo, na primeira conexão ou ao voltar de uma aba em segundo plano), o relógio pula direto para o `target`.

### Interpolação (`interpolatedPlayers`)

Com `renderTick` em mãos, por exemplo 1041,4:

1. encontra no histórico o snapshot `older` com tick ≤ 1041,4 (o 1041) e o `newer` com tick ≥ 1041,4 (o 1042);
2. calcula `alpha = (1041,4 − 1041) / (1042 − 1041) = 0,4`;
3. para cada jogador: `x = older.x + (newer.x − older.x) × 0,4`, e o mesmo para `y`.

Casos de borda:
- **`renderTick` além do snapshot mais novo** (rede ruim, atraso de interpolação pequeno demais): desenha a posição mais nova, sem extrapolar. O personagem para por um instante.
- **Jogador que só existe no snapshot mais novo** (acabou de entrar): aparece direto na posição nova.
- **O seu próprio personagem** nunca é interpolado: ele usa a predição.

### A escolha dos 100 ms

O atraso precisa cobrir o intervalo entre snapshots mais o jitter da rede, para que quase sempre exista um snapshot "depois" do ponto desenhado:

| Tick rate | Intervalo | 100 ms cobrem |
|---|---|---|
| 60 Hz | 16,7 ms | 6 ticks: folga para perda e jitter |
| 20 Hz | 50 ms | 2 ticks: absorve um snapshot perdido |
| 5 Hz | 200 ms | **menos de 1 tick**: o cliente fica sem o snapshot seguinte com frequência e os bots param e andam |

Com 5 Hz, aumente o atraso para ~400 ms no painel e o movimento volta a ficar suave, só que mais atrasado. É a troca central da interpolação: **mais atraso, mais suavidade**.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| **Desenhar o último snapshot** | Degraus e travadas (desligue **Interpolação** a 5 Hz para ver). |
| **Extrapolação / dead reckoning** (prever os outros pela velocidade atual) | Mostra os outros "no presente", mas erra sempre que alguém muda de direção, e a correção aparece como um salto. Funciona bem para veículos com inércia; para personagens que mudam de direção a qualquer momento, erra muito. |
| **Relógio de renderização baseado no horário de chegada** | Mais simples, mas o jitter passaria direto para o movimento. Usar o tick do servidor como referência isola a renderização das variações da rede. |

## Consequências

**Positivas**
- Os outros jogadores se movem suavemente com qualquer tick rate, desde que o atraso cubra o intervalo entre snapshots.
- Jitter e perdas ocasionais ficam invisíveis.

**Negativas**
- **Você vê os outros no passado:** 100 ms de atraso de interpolação mais ½ RTT de rede. Com 150 ms de RTT, um bot é desenhado onde estava ~175 ms atrás. Mirar onde o inimigo aparece significa mirar onde ele estava, e o servidor precisa de *lag compensation* para aceitar o acerto ([ADR 0010](0010-tiro-com-lag-compensation.md)). O mesmo `renderTick` usado aqui é enviado com cada tiro como `viewTick`, e o servidor repete esta mesma interpolação no seu histórico.
- Orbes e placar não são interpolados: aparecem e somem no último snapshot recebido.

## Como verificar

- Mude o **Tick rate** para 5 Hz e desligue **Interpolação**: os bots teleportam a cada 200 ms. Ligue de novo: movimento suave, mas há pausas, porque 100 ms é menos que um tick. Aumente o **Atraso da interpolação** para 300–400 ms e as pausas somem.
- No preset **Rede ruim (UDP)**, com interpolação, os bots continuam suaves mesmo com 10% de perda e 40 ms de jitter.
