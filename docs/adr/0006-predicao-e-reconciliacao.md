# ADR 0006: Predição e reconciliação no cliente

- **Status:** aceita
- **Data:** 2026-10-02

## Contexto

Com o servidor autoritativo ([ADR 0001](0001-servidor-autoritativo-com-tick-fixo.md)), o caminho de uma tecla até a tela é:

```
você aperta → input viaja (½ RTT) → servidor espera o próximo tick (até 50 ms)
            → snapshot viaja (½ RTT) → tela atualiza
```

Com 150 ms de RTT e 20 Hz, o personagem reagiria entre 150 e 200 ms depois de cada tecla. Qualquer coisa acima de ~100 ms já é perceptível, e o jogo parece "pesado".

## Decisão

1. **Predição:** o cliente aplica cada input **na mesma hora**, localmente, com a mesma física do servidor ([ADR 0005](0005-fisica-deterministica-compartilhada.md)). O personagem responde em 0 ms.
2. **Reconciliação:** quando chega um snapshot, o cliente **descarta a sua previsão**, parte da posição oficial do servidor e **reaplica os inputs que o servidor ainda não processou**.

As duas podem ser desligadas no painel para ver o que acontece sem cada uma.

## Como funciona em detalhe

### O estado no cliente

[`main.js`](../../src/main/resources/static/js/main.js) mantém:

- `me`: a posição **prevista** do seu personagem, a que aparece na tela;
- `pending`: inputs enviados e ainda não confirmados pelo servidor ([ADR 0004](0004-inputs-sequenciados-e-redundantes.md)).

### Predição (`sampleInput`)

```js
const input = { seq: ++seq, buttons: currentButtons() };
pending.push(input);
if (settings.prediction && me) me = step(me, input.buttons, constants);
```

O input é aplicado na posição prevista no mesmo instante em que é enviado.

### Reconciliação (`onSnapshot`)

```js
pending = pending.filter((input) => input.seq > snapshot.ackSeq);

let corrected = { x: server.x, y: server.y };
for (const input of pending) corrected = step(corrected, input.buttons, constants);

stats.correction = Math.hypot(me.x - corrected.x, me.y - corrected.y);
me = corrected;
```

Passo a passo, com 150 ms de RTT e você andando para a direita a 3,67 px por input:

```
seq:        1   2   3   4   5   6   7   8   9   10  11  12
enviado em: 0   16  33  50  66  83  100 116 133 150 166 183 (ms)

t=183 ms: a previsão aplicou os inputs 1..12 → me.x = 100 + 12 × 3,67 = 144,0

          chega snapshot: servidor processou até o seq 3 → server.x = 100 + 3 × 3,67 = 111,0
          pending = [4..12]  (9 inputs ainda em trânsito ou na fila do servidor)
          corrected = 111,0 + 9 × 3,67 = 144,0

          correção = |144,0 − 144,0| = 0 px  ✓
```

O snapshot diz onde você estava **no passado** (no input 3). Reaplicar os inputs 4 a 12 traz essa posição oficial para o **presente**. Se a previsão estava certa, o resultado é idêntico, e a correção é zero.

### Quando a correção não é zero

A previsão erra quando o servidor simula algo diferente do que o cliente simulou:

- **Inputs perdidos de vez** (mais de 0,5 s de perda, [ADR 0004](0004-inputs-sequenciados-e-redundantes.md)): o servidor nunca aplicou alguns inputs.
- **Inputs descartados pelo servidor** por excederem o limite por tick ou o tamanho da fila ([ADR 0001](0001-servidor-autoritativo-com-tick-fixo.md)).
- **Algo que o cliente não simula.** Neste jogo, jogadores atravessam uns aos outros. Se houvesse colisão entre jogadores ou empurrões, o cliente não saberia prever, porque não conhece os inputs dos outros.

Nesses casos `me` "pula" para a posição corrigida. Jogos reais suavizam esse pulo, interpolando a posição desenhada até a corrigida em alguns quadros. Aqui o pulo é mostrado cru, para ficar visível.

### O que acontece com cada técnica desligada

| Predição | Reconciliação | Comportamento | Por quê |
|---|---|---|---|
| ✅ | ✅ | Resposta instantânea, sem trancos (correção 0 px) | O modelo completo |
| ✅ | ❌ | Responde na hora, mas **dá trancos para trás** ao mudar de direção (correção de ~15–18 px nos testes) | A cada snapshot, `me` volta para a posição oficial, que está ~1 RTT atrasada, sem reaplicar o que está em trânsito |
| ❌ | — | Cada tecla demora ~1 RTT para ter efeito | `me` é sempre a posição oficial do último snapshot |

Um detalhe do caso sem reconciliação: andando em linha reta, os trancos quase não aparecem. A posição oficial e a prevista avançam o mesmo tanto entre um snapshot e o seguinte, então a diferença se mantém. O efeito aparece ao **mudar de direção, começar ou parar**, porque aí a posição oficial ainda reflete a direção antiga. Por isso o teste automatizado alterna entre esquerda e direita.

### O contorno tracejado

Com **Mostrar posição do servidor** ligado, um círculo tracejado marca a posição do último snapshot, sem reconciliação. Ele mostra a distância entre o que você vê e o que o servidor sabe. Com 150 ms de RTT, andando, ele segue ~10 inputs (~37 px) atrás da sua bolinha.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| **Sem predição** (esperar o servidor) | Aceitável em jogos por turnos ou lentos. Num jogo de movimento, cada 100 ms de RTT viram 100 ms de atraso no controle. |
| **Predição sem reconciliação, com interpolação suave para a posição do servidor** | Esconde os trancos, mas a previsão fica sempre puxada para trás, como se o personagem estivesse "na lama". |
| **Cliente autoritativo para o próprio movimento** (o servidor só valida) | Resposta instantânea sem reconciliação, mas a validação precisaria aceitar alguma tolerância, e essa tolerância é exatamente a margem que um trapaceiro explora. |

## Consequências

**Positivas**
- O seu personagem responde em 0 ms com qualquer latência.
- O servidor continua sendo a fonte da verdade: a previsão é sempre substituída pela versão oficial.

**Negativas**
- **Você vê o seu personagem no presente e os outros no passado** ([ADR 0007](0007-interpolacao-de-entidades-remotas.md)). Com tiro, isso exige *lag compensation* no servidor, implementada no [ADR 0010](0010-tiro-com-lag-compensation.md).
- A reconciliação reaplica todos os inputs pendentes a cada snapshot: até 30 chamadas de `step`, 20 vezes por segundo. É barato aqui, mas cresce com física mais cara.
- Eventos que o cliente não prevê (coletar o orbe) só aparecem depois de um RTT. Com 150 ms, você passa por cima do orbe e ele só some um instante depois.

## Como verificar

- Preset **150 ms RTT**:
  - desligue **Predição no cliente** e sinta o atraso;
  - religue e desligue só **Reconciliação**, alternando entre esquerda e direita: o personagem dá trancos, e **Correção** mostra o tamanho deles;
  - com tudo ligado, **Correção** fica em 0.0 px.
- O teste automatizado no navegador usado no desenvolvimento mediu correção máxima de 0 px com reconciliação e de 14,7 a 18,3 px sem ela, alternando direções a cada 150 ms.
