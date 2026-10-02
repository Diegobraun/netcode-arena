# Glossário

Termos usados no jogo, no painel e nos [ADRs](adr/README.md), agrupados por assunto. Cada definição vale para **este projeto**: em outros jogos os números mudam, mas as ideias são as mesmas.

Termos marcados com *(painel)* aparecem com esse nome no painel do jogo.

## Simulação no servidor

**Netcode**
O conjunto de técnicas que fazem um jogo online parecer responsivo apesar da latência e da perda de pacotes: tudo o que este projeto demonstra.

**Servidor autoritativo**
O servidor é a única fonte da verdade: só ele move jogadores, decide quem pegou um orbe e quem foi atingido. O cliente envia intenções e desenha o resultado. Evita conflitos ("no meu PC eu peguei primeiro") e trapaças de posição. [ADR 0001](adr/0001-servidor-autoritativo-com-tick-fixo.md)

**Tick**
Um passo da simulação no servidor. Em cada tick o servidor aplica os inputs recebidos, move os bots, resolve tiros e coletas, grava o histórico de posições e envia um snapshot. Cada tick tem um número que só cresce (tick 1041, 1042...), usado como "relógio" compartilhado entre servidor e cliente.

**Tick rate** *(painel)*
Quantos ticks por segundo o servidor executa. Padrão de 20 Hz, ou seja, um tick a cada 50 ms. Pode ser 5, 10, 20, 30 ou 60 Hz. Mais ticks deixam o mundo mais atual e suave, ao custo de banda e CPU. Não muda a velocidade dos jogadores, porque o movimento é medido em inputs.

**Tick do servidor** *(painel)*
O número do último tick recebido pelo cliente.

**Bot**
Jogador controlado pelo servidor, que usa a mesma física dos humanos. Vai até o orbe mais próximo que nenhum outro bot alcança antes. Existe para a arena ter alvos em movimento mesmo com uma pessoa só.

**Orbe**
O objeto coletável da arena. Vale 1 ponto e reaparece em outro lugar. A arena mantém 12.

**Respawn**
Reaparecer em outro ponto da arena. Acontece com orbes coletados e com jogadores atingidos por um tiro.

## Mensagens

**Input** *(painel)*
Um comando do cliente: quais teclas estavam pressionadas durante 1/60 s, com um número de sequência e, se houve tiro, a mira e o `viewTick`. O cliente gera 60 por segundo, independentemente do tick rate. [ADR 0004](adr/0004-inputs-sequenciados-e-redundantes.md)

**Número de sequência (`seq`)**
O número de cada input (1041, 1042...). Permite ao servidor descartar repetidos, ao cliente saber o que já foi confirmado e à reconciliação saber de onde reaplicar.

**Redundância**
Cada pacote de inputs leva também os inputs anteriores ainda não confirmados (até 30). Se um pacote se perde, o seguinte traz os mesmos comandos. É assim que o jogo resiste à perda sem retransmissão.

**Ack** (*acknowledgement*)
Confirmação. Aqui, o `ackSeq` que vai em cada snapshot: o número do último input daquele jogador que o servidor já processou. O cliente para de reenviar tudo até esse número.

**Inputs sem ack** *(painel)*
Quantos inputs o cliente enviou e o servidor ainda não confirmou. Com RTT de 150 ms, fica em torno de 10: 60 inputs/s × 0,15 s, mais a espera pelo próximo tick. Se crescer muito, a rede está perdendo ou atrasando.

**Snapshot** *(painel)*
A mensagem que o servidor envia a cada tick com o estado do mundo: posição e pontos de cada jogador, orbes, `ackSeq` e tiros daquele tick. Com 7 jogadores e 12 orbes, ocupa 236 bytes. [ADR 0003](adr/0003-protocolo-binario-e-json-de-controle.md)

**Protocolo binário**
Os inputs e snapshots são bytes num layout fixo (números em big-endian e posições em `float32`), em vez de texto. Ocupa cerca de 4 vezes menos que o mesmo snapshot em JSON.

**JSON de controle**
As mensagens raras (boas-vindas, ping, ajustes do painel e mudança de configuração) usam JSON, que é mais fácil de ler e depurar. Só o tráfego de alta frequência usa binário.

**Big-endian**
A ordem dos bytes de um número no fio: o byte mais significativo vem primeiro. É o padrão de `DataView` no JavaScript e de `ByteBuffer` no Java, então os dois lados concordam sem configuração.

**`float32` / `float64`**
Números de ponto flutuante de 4 e 8 bytes. As posições viajam em `float32` para economizar espaço; a física roda em `float64` (`double`).

## Rede

**Latência** *(painel)*
O tempo que uma mensagem leva para ir de um lado ao outro. O painel configura a latência de cada sentido.

**RTT** (*round-trip time*) *(painel)*
O tempo de ida e volta. Com 75 ms em cada sentido, o RTT é 150 ms. O cliente mede com mensagens de `ping` e `pong`.

**Ping / pong**
O cliente envia `ping` com o horário de envio, o servidor devolve `pong` com o mesmo horário, e a diferença para o horário atual é o RTT.

**Jitter** *(painel)*
A variação da latência. Com 100 ms de latência e 40 ms de jitter, cada mensagem leva entre 100 e 140 ms. Faz mensagens chegarem irregulares e, no UDP, fora de ordem.

**Perda de pacotes** *(painel)*
A fração de mensagens que não chegam. Com 10%, uma em cada dez se perde.

**Fora de ordem** *(painel)*
Snapshots que chegaram depois de um mais novo. O cliente os descarta. Só acontece no modo UDP com jitter.

**Upload / Download** *(painel)*
Os bytes por segundo que o cliente envia (inputs) e recebe (snapshots).

**UDP**
Protocolo de transporte sem garantias: cada pacote chega ou não, na ordem que for. Nada espera nada. É o padrão dos jogos de ação, que tratam a perda no próprio jogo.

**TCP**
Protocolo de transporte com entrega garantida e em ordem: um pacote perdido é retransmitido, e os seguintes esperam por ele. Ótimo para arquivos e páginas, ruim para estado em tempo real.

**Head-of-line blocking**
O efeito colateral do TCP: um pacote perdido segura a entrega de todos os que vieram depois, mesmo que já tenham chegado. No jogo, os snapshots param e chegam de uma vez, em rajada.

**Retransmissão**
Reenviar um pacote perdido. O TCP detecta a perda por ACKs duplicados (*fast retransmit*, cerca de 1 RTT) ou por tempo esgotado (*RTO*, mínimo de 200 ms no Linux). O simulador aplica uma penalidade de `max(200 ms, 2 × latência)`.

**WebSocket**
Conexão bidirecional e persistente entre navegador e servidor, sobre TCP. É o transporte real deste projeto, porque navegadores não falam UDP puro. [ADR 0002](adr/0002-websocket-com-simulador-de-rede.md)

**Simulador de rede**
Componente do servidor que atrasa, descarta e reordena mensagens de cada conexão, imitando UDP ou TCP sobre uma rede ruim. Permite comparar os dois modos no navegador.

**Uplink / downlink**
Os dois sentidos da conexão simulada: uplink do cliente para o servidor (inputs, ping) e downlink do servidor para o cliente (snapshots, pong). Cada um tem as próprias condições.

**Canal confiável**
No modo UDP, mensagens marcadas como confiáveis nunca são descartadas pelo simulador. É usado pela mensagem `config`, que avisa mudanças de tick rate e de bots.

## Cliente

**Predição** (*client-side prediction*)
O cliente aplica os próprios inputs na hora, com a mesma física do servidor, sem esperar a resposta. É o que faz o personagem responder ao toque da tecla mesmo com 150 ms de RTT. [ADR 0006](adr/0006-predicao-e-reconciliacao.md)

**Reconciliação** (*server reconciliation*)
Quando chega um snapshot, o cliente parte da posição oficial do servidor e reaplica os inputs que o servidor ainda não processou. Corrige erros da predição sem desfazer o que o jogador já fez.

**Correção** *(painel)*
Quanto a reconciliação moveu o personagem, em pixels. Com predição e física iguais, fica em 0 px. Sobe quando a reconciliação está desligada ou quando inputs se perdem de vez.

**Fantasma do servidor**
O contorno tracejado em volta do seu personagem: a posição oficial no último snapshot, atrasada em cerca de 1 RTT em relação à posição prevista.

**Interpolação** (*entity interpolation*)
Os outros jogadores são desenhados um pouco no passado, numa posição calculada entre dois snapshots recebidos. Deixa o movimento suave com 20 atualizações por segundo e esconde jitter e perdas. [ADR 0007](adr/0007-interpolacao-de-entidades-remotas.md)

**Atraso da interpolação** *(painel)*
Quanto no passado os outros são desenhados. Padrão de 100 ms, ou 2 ticks a 20 Hz. Precisa cobrir o intervalo entre snapshots mais o jitter: mais atraso dá mais suavidade, mas mostra o mundo mais velho.

**`renderTick`**
O "relógio" da interpolação: um tick fracionário (por exemplo 1041,4) que diz qual instante do servidor desenhar agora. Avança no ritmo do tempo real e é ajustado aos poucos em direção a "último tick recebido − atraso".

**Extrapolação** / ***dead reckoning***
Prever a posição dos outros pela velocidade atual, para mostrá-los "no presente". Erra sempre que alguém muda de direção. Este projeto não usa.

**Teleporte**
Um salto de mais de 100 px entre dois snapshots, como num respawn. A interpolação não desliza entre os dois pontos: o jogador aparece direto no lugar novo.

## Tiro

**Hitscan**
Tiro instantâneo: um raio sai do atirador e acerta o primeiro jogador no caminho, sem projétil viajando pela arena. [ADR 0010](adr/0010-tiro-com-lag-compensation.md)

**Lag compensation**
O servidor volta no tempo até o instante que o atirador estava vendo e checa o acerto contra as posições daquele instante. Sem isso, como os outros são desenhados no passado, quem mira certo na tela erra no servidor.

**`viewTick`**
O `renderTick` do atirador no momento do clique, enviado junto com o tiro. Diz ao servidor para qual instante voltar.

**Rewind** *(painel: Último rewind)*
Quanto o servidor voltou no tempo para checar um tiro. Na prática, RTT + atraso da interpolação + até 1 tick: cerca de 300 ms com 150 ms de RTT. Limitado a 1 s.

**Histórico de posições**
As posições de todos os jogadores no último segundo, gravadas a cada tick. É de onde o servidor tira as posições passadas para o rewind.

**Cooldown**
O intervalo mínimo entre dois tiros: 24 inputs (400 ms). Contado em inputs, e não em milissegundos, para cliente e servidor concordarem exatamente.

**Tracer**
A linha desenhada do atirador até o ponto onde o tiro parou. O cliente desenha um tracer imediato na hora do clique e outro quando o servidor confirma.

**Acertos / tiros** *(painel)*
Quantos dos seus tiros o servidor confirmou como acerto.

**Tiro atrás da parede** (*shot behind cover*)
O efeito colateral da lag compensation: a vítima pode ser atingida depois de já ter saído da mira na própria tela, porque o servidor deu razão ao que o atirador via.

## Física

**Física determinística**
A mesma entrada sempre produz exatamente o mesmo resultado, até o último bit. O Java e o JavaScript usam a mesma função, com as mesmas operações na mesma ordem, e chegam ao mesmo número. É o que torna a predição exata. [ADR 0005](adr/0005-fisica-deterministica-compartilhada.md)

**IEEE 754**
O padrão de números de ponto flutuante usado tanto pelo `double` do Java quanto pelo `number` do JavaScript. Soma, multiplicação, divisão e raiz quadrada dão o mesmo resultado nos dois.

**Passo fixo**
Cada input sempre representa exatamente 1/60 s de movimento, independentemente da taxa de quadros da tela ou do tick rate. Sem isso, a física daria resultados diferentes em máquinas diferentes.

## Arquitetura

**`game-loop`**
A thread única dona do mundo: só ela lê e altera posições, placar, orbes, histórico e tiros. Tudo o que vem de outras threads vira uma tarefa enfileirada nela. [ADR 0008](adr/0008-modelo-de-threads.md)

**`net-sim`**
A thread do simulador de rede. Entrega as mensagens no instante simulado e é a única que chama `sendMessage`.

**Lockstep**
Modelo alternativo, comum em jogos de estratégia: todos simulam tudo e trocam só inputs, avançando juntos. Exige determinismo perfeito e trava todos no ritmo do mais lento. Não usado aqui.

**Delta compression**
Enviar só o que mudou desde o último snapshot confirmado, em vez do estado inteiro. Reduz muito a banda em jogos grandes. Não implementado aqui. [ADR 0009](adr/0009-fora-do-escopo-e-proximos-passos.md)

**ADR** (*Architecture Decision Record*)
Documento curto que registra uma decisão: o contexto, o que foi escolhido, as alternativas descartadas e as consequências.
