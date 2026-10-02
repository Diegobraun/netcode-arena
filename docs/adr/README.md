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
| [0010](0010-tiro-com-lag-compensation.md) | Tiro instantâneo com lag compensation | Como acertar quem você vê no passado? |

## Vocabulário

Os termos usados nos ADRs (tick, snapshot, ack, RTT, jitter, head-of-line blocking, predição, interpolação, rewind e outros) estão definidos no [glossário](../glossario.md).
