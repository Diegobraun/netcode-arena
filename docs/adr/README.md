# Decisões de arquitetura (ADRs)

Cada ADR (*Architecture Decision Record*) registra uma decisão: o problema que existia, o que foi escolhido, as alternativas descartadas e as consequências, incluindo as ruins. Aqui eles também explicam em detalhe como cada parte funciona, com referências ao código e a forma de verificar o comportamento no próprio jogo.

A ordem abaixo é a ordem sugerida de leitura: do servidor para o cliente, da rede para a tela.

| # | Decisão | Pergunta que responde |
|---|---|---|
| [0001](0001-servidor-autoritativo-com-tick-fixo.md) | Servidor autoritativo com tick fixo | Quem decide onde cada jogador está? |
| [0002](0002-websocket-com-simulador-de-rede.md) | WebSocket como transporte, com simulador de UDP e TCP | Por que não UDP de verdade, e como o simulador funciona? |
| [0003](0003-protocolo-binario-e-json-de-controle.md) | Protocolo binário para o jogo, JSON para controle | O que exatamente trafega no fio, byte a byte? |
| [0004](0004-inputs-sequenciados-e-redundantes.md) | Inputs numerados e reenviados até o ack | Como não perder comandos sem usar TCP? |
| [0005](0005-fisica-deterministica-compartilhada.md) | Mesma física, determinística, no servidor e no cliente | Como o cliente consegue prever o servidor? |
| [0006](0006-predicao-e-reconciliacao.md) | Predição e reconciliação no cliente | Por que o personagem responde na hora com 150 ms de RTT? |
| [0007](0007-interpolacao-de-entidades-remotas.md) | Interpolação dos outros jogadores | Por que os outros se movem suave com só 20 atualizações/s? |
| [0008](0008-modelo-de-threads.md) | Uma thread dona do mundo, outra da rede | Como evitar condições de corrida sem locks no jogo? |
| [0009](0009-fora-do-escopo-e-proximos-passos.md) | O que ficou de fora | O que faltaria para um jogo de produção? |

## Vocabulário

| Termo | Significado aqui |
|---|---|
| **Tick** | Um passo da simulação no servidor. A 20 Hz, um tick a cada 50 ms. |
| **Snapshot** | Mensagem do servidor com o estado do mundo num tick: posição de todos os jogadores e orbes. |
| **Input** | Um comando do cliente: quais direções estavam pressionadas durante 1/60 s, com um número de sequência. |
| **Ack** | O número do último input que o servidor já processou para aquele jogador. Vai dentro de cada snapshot. |
| **RTT** | *Round-trip time*: tempo de ida e volta de uma mensagem. Com 75 ms de latência em cada sentido, o RTT é 150 ms. |
| **Jitter** | Variação da latência. Com jitter de 40 ms, cada mensagem leva entre a latência base e latência + 40 ms. |
| **Head-of-line blocking** | No TCP, um pacote perdido segura a entrega de todos os seguintes até ser retransmitido. |
| **Autoritativo** | O servidor é a fonte da verdade. O cliente só sugere (inputs) e mostra (renderiza). |
