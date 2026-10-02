# ADR 0010: Tiro instantâneo com lag compensation

- **Status:** aceita
- **Data:** 2026-10-02

## Contexto

O jogo ganhou tiro: clicar dispara um raio instantâneo (*hitscan*) na direção do mouse. Acertar outro jogador ou um bot vale 3 pontos, e o alvo renasce em outro ponto da arena.

O problema aparece quando se junta isso ao que os ADRs anteriores construíram. Quando você clica num bot, vê esse bot **no passado**:

- ~100 ms de atraso de interpolação ([ADR 0007](0007-interpolacao-de-entidades-remotas.md));
- mais ½ RTT, o tempo que o snapshot levou para chegar.

E o seu tiro chega ao servidor ½ RTT **depois** do clique. Somando, o servidor recebe o tiro quando o alvo já andou RTT + atraso de interpolação desde a posição que você viu. Com 150 ms de RTT são ~300 ms. A 220 px/s, o bot andou ~65 px, e o raio do bot é 14 px.

Sem nenhuma compensação, para acertar seria preciso mirar à frente do alvo, adivinhando o próprio ping e a direção em que ele vai andar. Cada jogador teria uma mira diferente dependendo da sua conexão.

## Decisão

O servidor **volta no tempo** para checar o acerto, usando exatamente o instante que o atirador estava vendo. É o modelo do Source Engine (Counter-Strike, Team Fortress 2) e, com variações, o da maioria dos FPS.

1. **O tiro viaja dentro do input.** O input em que você clicou ganha o bit `FIRE` e, junto, o ponto mirado e o `viewTick`: o tick, possivelmente fracionário, em que os outros jogadores estavam sendo desenhados na sua tela naquele instante.
2. **O servidor guarda um histórico de posições** de todos os jogadores, um registro por tick, cobrindo o último segundo.
3. **Ao processar o tiro**, o servidor reconstrói as posições no `viewTick`, interpolando entre dois ticks do histórico **da mesma forma que o cliente fez**. Ele traça o raio a partir da posição atual do atirador, encontra o primeiro alvo atingido e volta ao presente.
4. **O resultado vai no snapshot** como um evento de tiro: quem atirou, quem foi atingido, de onde até onde, onde o servidor viu o alvo e quanto voltou no tempo. Todos os clientes desenham o tracer, e o atirador vê a confirmação.
5. **A compensação pode ser desligada** por jogador, no painel, para comparar.

## Como funciona em detalhe

### 1. O clique vira um input (cliente)

[`main.js`](../../src/main/resources/static/js/main.js), em `sampleInput`:

```js
if (fireRequested && aim && seq - lastShotSeq >= constants.shotCooldownInputs) {
  input.shot = { aimX: aim.x, aimY: aim.y, viewTick: viewTick() };
  lastShotSeq = seq;
}
```

- O clique só marca `fireRequested`. O tiro sai no próximo input, a cada 1/60 s, para ficar **ordenado com o movimento**: o servidor vai mover o atirador por aquele input e só então atirar, exatamente como a predição do cliente fez.
- `viewTick()` devolve o tick que está sendo desenhado: o `renderTick` da interpolação, limitado ao intervalo de snapshots do histórico. Sem interpolação, devolve o tick do último snapshot. O limite importa. Quando a rede trava (modo TCP), o relógio de renderização passa do snapshot mais novo, mas a tela continua mostrando esse snapshot. Mandar o `renderTick` sem limite fazia o servidor voltar para um instante diferente do desenhado. Essa versão errou 3 de 12 tiros na rede ruim com TCP; corrigida, acertou 12 de 12.
- O cooldown de 24 inputs (400 ms) é verificado no cliente e, de novo, no servidor.

### 2. O formato no fio

Um input com tiro ocupa 21 bytes em vez de 5 ([ADR 0003](0003-protocolo-binario-e-json-de-controle.md)):

```
u32  seq
u8   buttons       bit 16 = FIRE
f32  aimX          ponto mirado, em coordenadas da arena
f32  aimY
f64  viewTick      tick fracionário que estava na tela
```

Como o tiro está **dentro do input**, ele herda tudo o que o [ADR 0004](0004-inputs-sequenciados-e-redundantes.md) construiu: é reenviado até o ack, sobrevive a perda de pacotes e é processado **uma única vez**, porque o servidor descarta `seq` repetido. Não foi preciso criar um canal confiável só para tiros.

### 3. O histórico no servidor

[`PositionHistory`](../../src/main/java/br/com/diegobraun/netcode/game/PositionHistory.java) guarda, ao fim de cada tick, um mapa `id → posição` de todos os jogadores. É o mesmo estado que vai no snapshot daquele tick. O tamanho é `ceil(1 s × tickRate) + 2` registros: 22 a 20 Hz.

`positionsAt(tick)` reconstrói qualquer instante dentro da janela:

```java
for each jogador presente no registro mais novo:
    from = posição no registro anterior
    se from não existe ou a distância > 100 px (TELEPORT_DISTANCE):
        usa a posição nova (sem interpolar)
    senão:
        from + (to − from) × alpha
```

A regra do teletransporte existe nos dois lados. Quando um alvo é atingido e renasce do outro lado da arena, interpolar entre a posição antiga e a nova faria o personagem "deslizar" pela arena por um tick. 100 px por tick está bem acima do que um jogador consegue andar (44 px por tick a 5 Hz, o tick mais lento), então só um respawn ultrapassa o limite.

### 4. O teste de acerto (`GameWorld.fire`)

```java
origin = posição atual do atirador        // já movida por este input
direção = normalizar(aim − origin)
alvos = compensado ? history.positionsAt(viewTick) : posições atuais
alcance = distância até a borda da arena na direção do tiro

para cada alvo (exceto o atirador):
    along  = projeção do alvo na direção do tiro    // quão longe no raio
    across = distância do alvo até a reta do raio   // quão longe para o lado
    se along ≥ 0 e along ≤ alcance e across ≤ raio do jogador:
        alcance = along; atingido = alvo             // o mais próximo vence
```

É a interseção clássica entre raio e círculo: o alvo é atingido se a reta passa a menos de um raio do centro dele, na frente do atirador. Guardar o `along` menor garante que o tiro para no primeiro alvo, e um alvo atrás de outro fica protegido.

Detalhes:
- **A origem não é rebobinada.** O atirador está no presente do servidor, na mesma posição que a predição dele mostrava ([ADR 0006](0006-predicao-e-reconciliacao.md)). Só os **alvos** voltam no tempo, porque só eles estavam no passado na tela do atirador.
- **Limite de 1 s.** O `viewTick` é limitado a `[agora − 1 s, tick mais novo do histórico]`. Sem limite, um cliente poderia mandar um `viewTick` arbitrário e acertar alvos onde eles estiveram há muito tempo. Jogos reais usam limites parecidos: o Source Engine aceita até 1 s por padrão.
- **Atingido renasce** em posição aleatória, e o atirador ganha 3 pontos.

### 5. O evento no snapshot

Cada tiro processado gera um `ShotEvent`, enviado no snapshot do tick (31 bytes):

```
u16  shooterId
u16  hitId            0 = errou
u8   flags            1 = compensado
f32  originX, originY
f32  endX, endY       onde o raio parou (no alvo ou na borda)
f32  targetX, targetY onde o servidor viu o alvo ao checar o acerto
u16  rewindMs         quanto o servidor voltou no tempo
```

No cliente:
- **o seu tiro** aparece na hora, como um tracer fino e predito, sem esperar o servidor;
- **a confirmação** chega ~1 RTT depois: o tracer oficial, um círculo tracejado vermelho onde o servidor viu o alvo e o texto `+3 · rewind 311 ms`;
- **tiros de outros jogadores** aparecem como tracers.

O acerto não é previsto no cliente: o placar e o respawn do alvo só mudam quando o servidor confirma. Alguns jogos preveem o efeito do acerto (sangue, marcador) para dar resposta imediata. Aqui a espera foi mantida visível.

## Resultados medidos

Teste automatizado no navegador: o script encontra um bot pelos pixels do canvas, onde ele **aparece na tela**, e clica no centro dele, com 6 bots se movendo:

| Rede | Com compensação | Sem compensação | Rewind típico |
|---|---|---|---|
| LAN (RTT ~2 ms) | 12/12 | 4 a 7 de 12 | ~125 ms |
| 150 ms RTT | 15/15 | 3 a 6 de 15 | ~290–310 ms |
| Rede ruim, TCP (RTT ~360 ms) | 36/36 (3 rodadas de 12) | — | ~500–600 ms |

Os intervalos vêm de várias execuções. Sem compensação, o resultado depende de para onde os bots estavam andando: um bot que anda na direção do raio continua sendo atingido.

No TCP, contar os acertos logo após o último tiro dava 11/12. A confirmação do último tiro ainda estava presa atrás de uma retransmissão. Esperando 3 s, todas as rodadas fecharam em 12/12. Na prática, quanto pior a rede, mais tempo o atirador espera para **ver** o acerto, mas o acerto é decidido corretamente.

- **Mesmo na LAN, sem compensação, boa parte dos tiros erra.** O atraso de interpolação sozinho (100 ms) já deixa o bot ~22 px longe de onde aparece, mais que o raio de 14 px.
- **O rewind medido bate com a teoria:** RTT + atraso de interpolação + até um tick esperando o processamento. Com 150 ms de RTT: 150 + 100 + ~50 ≈ 300 ms.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| **Sem compensação** ("mire à frente") | Cada jogador precisaria de uma mira diferente conforme o ping. Nos testes, de 3 a 4 acertos em 12 a 15 tiros. |
| **Cliente decide o acerto** ("acertei o jogador 3") | Resposta perfeita, mas trapaça trivial: o cliente diz que acertou quem quiser. O servidor poderia validar com tolerância, mas a tolerância é a margem do trapaceiro. |
| **Projéteis com velocidade** (balas que viajam) | Precisam de outra técnica: o projétil é simulado no servidor e o cliente o prevê ou o desenha adiantado. É o modelo de jogos com foguetes e flechas. Hitscan deixa a lag compensation isolada e fácil de ver. |
| **Usar o horário do servidor em vez do `viewTick`** (o servidor estima o que o cliente via a partir do RTT) | Funciona na média, mas erra com jitter e com o atraso de interpolação configurável. O cliente sabe exatamente o que estava desenhando; o servidor só pode estimar. |
| **Rebobinar o atirador também** | O atirador, na tela dele, está no presente (predição). Rebobinar a origem criaria um erro igual ao que a compensação tenta eliminar. |

## Consequências

**Positivas**
- O jogador mira onde vê o alvo, com qualquer ping, até o limite de 1 s.
- O tiro reutiliza toda a confiabilidade dos inputs, sem canal novo.
- O servidor continua autoritativo: ele refaz o teste com as próprias posições históricas. O cliente só informa o instante, e esse instante é limitado.

**Negativas**
- **"Morri atrás da parede."** A vítima vê o tiro acertá-la depois de já ter se movido: o atirador, com ping alto, viu e acertou uma posição antiga dela. É o efeito colateral inevitável da lag compensation: o atirador é favorecido em relação ao alvo. Jogos competitivos limitam o rewind justamente para limitar esse efeito.
- **Jogadores com ping alto acertam alvos "no passado"**, o que pode parecer injusto para quem tem ping baixo. Um limite de rewind menor (por exemplo 200 ms) reduz isso, ao custo de obrigar quem tem ping alto a mirar à frente.
- **Eventos de tiro podem se perder no modo UDP**, porque vão em snapshots não confiáveis. O placar continua correto (ele vem no estado), mas o tracer ou a confirmação daquele tiro não aparece, e o contador **Acertos / tiros** do painel pode ficar abaixo do real com perda alta.
- **O histórico custa memória proporcional a jogadores × tick rate**: aqui 22 mapas pequenos; num jogo grande, um *ring buffer* por jogador evita alocações.
- **Não há paredes**, então o raio só testa jogadores. Com paredes, o servidor precisaria também testar a geometria do mapa, que não se move e por isso não precisa de rewind.

## Como verificar

- Com o preset **150 ms RTT**, atire em bots se movendo, com **Lag compensation no servidor** ligada e desligada, e compare **Acertos / tiros**.
- Ao acertar, observe o círculo tracejado vermelho (onde o servidor viu o alvo) e o texto com o rewind.
- Testes: [`LagCompensationTest`](../../src/test/java/br/com/diegobraun/netcode/game/LagCompensationTest.java):
  - `rewindsTargetsToTheTickTheShooterWasSeeing`: acerta onde o alvo estava, com 150 ms de rewind;
  - `withoutCompensationTheSameShotMissesBecauseTheTargetMovedOn`: o mesmo tiro sem compensação erra;
  - `interpolatesBetweenTicksLikeTheClientDoes`: um `viewTick` fracionário (x,5) acerta a posição interpolada;
  - `rewindIsLimitedToOneSecond`: um `viewTick` antigo demais é limitado;
  - `cooldownIsCountedInInputsAndRedundantCopiesFireOnlyOnce`: cópias redundantes do mesmo input disparam uma vez;
  - `shotsStopAtTheNearestTarget`: o primeiro alvo no caminho bloqueia o tiro.
