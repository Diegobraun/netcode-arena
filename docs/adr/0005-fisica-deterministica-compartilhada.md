# ADR 0005: Mesma física, determinística, no servidor e no cliente

- **Status:** aceita
- **Data:** 2026-10-02

## Contexto

A predição no cliente ([ADR 0006](0006-predicao-e-reconciliacao.md)) consiste em o navegador calcular, sozinho, para onde o personagem vai, antes de o servidor responder. Isso só funciona se o cálculo do cliente der **exatamente** o mesmo resultado que o do servidor para os mesmos inputs. Se divergir, cada snapshot traz uma posição diferente da prevista, e o personagem fica dando trancos para se corrigir.

O servidor é Java e o cliente é JavaScript.

## Decisão

1. **A física é uma função pura e mínima**, `step(posição, botões) → nova posição`, implementada nas duas linguagens com as **mesmas operações, na mesma ordem**:
   - Java: [`Physics.step`](../../src/main/java/br/com/diegobraun/netcode/game/Physics.java)
   - JavaScript: `step` em [`physics.js`](../../src/main/resources/static/js/physics.js)
2. **O passo de tempo é fixo**: cada input representa exatamente 1/60 s. Nenhum dos lados usa o tempo real decorrido no cálculo.
3. **As constantes vêm do servidor** na mensagem `welcome`. O cliente não tem cópia própria de velocidade, tamanho da arena ou raio.

## Como funciona em detalhe

### A função

```java
public static Position step(Position position, int buttons) {
    double dx = ((buttons & RIGHT) != 0 ? 1 : 0) - ((buttons & LEFT) != 0 ? 1 : 0);
    double dy = ((buttons & DOWN) != 0 ? 1 : 0) - ((buttons & UP) != 0 ? 1 : 0);
    double length = Math.sqrt(dx * dx + dy * dy);
    if (length == 0) {
        return position;
    }
    double distance = SPEED * INPUT_DT / length;
    return new Position(
            clamp(position.x() + dx * distance, PLAYER_RADIUS, ARENA_WIDTH - PLAYER_RADIUS),
            clamp(position.y() + dy * distance, PLAYER_RADIUS, ARENA_HEIGHT - PLAYER_RADIUS));
}
```

```js
export function step(position, buttons, c) {
  const dx = (buttons & RIGHT ? 1 : 0) - (buttons & LEFT ? 1 : 0);
  const dy = (buttons & DOWN ? 1 : 0) - (buttons & UP ? 1 : 0);
  const length = Math.sqrt(dx * dx + dy * dy);
  if (length === 0) return position;
  const distance = (c.speed * (1 / c.inputRate)) / length;
  return {
    x: clamp(position.x + dx * distance, c.playerRadius, c.arenaWidth - c.playerRadius),
    y: clamp(position.y + dy * distance, c.playerRadius, c.arenaHeight - c.playerRadius),
  };
}
```

- **Teclas opostas se anulam** (direita + esquerda dá `dx = 0`).
- **A diagonal é normalizada**: sem dividir por `length` (√2 na diagonal), andar na diagonal seria 41% mais rápido.
- **A arena limita a posição** (`clamp`), descontando o raio do jogador.

### Por que o resultado é idêntico nas duas linguagens

- O `double` do Java e o `number` do JavaScript são o mesmo formato: IEEE 754 de 64 bits.
- Soma, subtração, multiplicação, divisão e `sqrt` são **corretamente arredondadas** pelo padrão IEEE 754. Mesma entrada, mesma operação: o mesmo bit no resultado, em qualquer linguagem e processador.
- `Math.max` e `Math.min` são exatos.
- A **ordem das operações** é a mesma. Ponto flutuante não é associativo: `(a × b) / c` pode diferir de `a × (b / c)` no último bit. Por isso `SPEED * INPUT_DT / length` no Java corresponde a `(c.speed * (1 / c.inputRate)) / length` no JS, e `INPUT_DT` é `1.0 / 60` nos dois.

A física foi mantida de propósito longe de funções como `sin`, `cos`, `exp` e `pow`. O padrão IEEE 754 não exige que elas sejam corretamente arredondadas, e as implementações podem diferir no último bit entre a JVM e os motores de JavaScript. Jogos com física mais rica que precisam de determinismo entre plataformas costumam usar aritmética de ponto fixo, só inteiros, por esse motivo.

### Onde o determinismo é quebrado, de propósito

O snapshot envia as posições como `float32` ([ADR 0003](0003-protocolo-binario-e-json-de-controle.md)). O servidor está em `x = 412.37129...` (double) e o cliente recebe `412.37128...` (float32). Na reconciliação, o cliente parte desse valor arredondado. A diferença é de no máximo ~0,00003 px nesta arena, e o painel mostra **Correção 0.0 px**.

### Passo fixo no cliente

O navegador desenha a tela no ritmo do monitor (60, 120, 144 Hz) com `requestAnimationFrame`, e esse ritmo varia. Os inputs, porém, são gerados num ritmo fixo de 60 por segundo, com um acumulador:

```js
accumulator += elapsed;
while (accumulator >= 1000 / 60) {
  sampleInput();
  accumulator -= 1000 / 60;
}
```

Num monitor de 144 Hz, alguns quadros geram zero inputs e outros um. Num travamento de 100 ms, o quadro seguinte gera 6 de uma vez. O total por segundo é sempre 60, então a física do cliente avança exatamente como a do servidor. O tempo decorrido por quadro é limitado a 250 ms, para que voltar de uma aba em segundo plano não gere centenas de inputs de uma vez.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| **Passo variável** (`posição += velocidade × dt_real`) | Cada lado teria um `dt` diferente, e a predição nunca bateria exatamente. |
| **Compartilhar o código** (física em TypeScript rodando no servidor via GraalJS, ou Java compilado para WebAssembly) | Garantiria o mesmo código, mas acrescentaria uma camada de build e tiraria a física de Java "puro". Com uma função de 10 linhas, duplicar com testes é mais simples. |
| **Ponto fixo** (posições em inteiros) | Garante determinismo mesmo com funções transcendentes, mas complica a leitura do código sem benefício para uma física sem `sin` e `cos`. |
| **Velocidade e aceleração** (física com inércia) | Mais realista, mas aumenta o estado a sincronizar e não acrescenta nada ao objetivo de demonstrar netcode. |

## Consequências

**Positivas**
- A predição acerta: em movimento contínuo, sem perda de inputs, a correção é 0 px.
- O movimento não depende do tick rate nem da taxa de quadros da tela.

**Negativas**
- A física existe em duas cópias que precisam evoluir juntas. Os testes cobrem o lado Java; não há teste automatizado que compare Java e JS lado a lado.
- Qualquer função não determinística adicionada à física (`Math.sin`, aleatoriedade) quebra a predição de forma sutil.

## Como verificar

- Com o preset **150 ms RTT**, ande em linha reta: **Correção** fica em 0.0 px. A predição e o servidor chegam no mesmo lugar.
- Testes: [`PhysicsTest`](../../src/test/java/br/com/diegobraun/netcode/game/PhysicsTest.java) (velocidade por input, normalização da diagonal, teclas opostas, limites da arena).
