# ADR 0009: O que ficou de fora

- **Status:** aceita
- **Data:** 2026-10-02

## Contexto

O projeto tem um objetivo didático: mostrar, de forma visível e mensurável, as técnicas centrais de netcode (servidor autoritativo, predição, reconciliação, interpolação) e a diferença entre UDP e TCP. Várias técnicas que um jogo de produção precisaria foram deixadas de fora de propósito, para manter o código pequeno o bastante para ser lido inteiro.

Este ADR registra o que ficou de fora, por que importa e como seria implementado.

## Decisão

Ficam fora do escopo os itens abaixo, em ordem aproximada de importância para um jogo real.

### 1. Lag compensation

**Problema:** você vê os outros jogadores ~100 ms (interpolação) + ½ RTT no passado ([ADR 0007](0007-interpolacao-de-entidades-remotas.md)). Num jogo com tiro, você mira onde o inimigo **aparece**, mas no servidor ele já está em outro lugar. Sem compensação, seria preciso mirar "na frente" do alvo, adivinhando o seu próprio ping.

**Como seria:** o servidor guarda um histórico das posições de todos os jogadores no último segundo. Quando chega um tiro, ele calcula o instante que o atirador estava vendo (tick atual − ½ RTT − atraso de interpolação), "volta no tempo" para as posições daquele instante, testa o acerto e volta ao presente. É a origem do clássico "morri atrás da parede": no instante que o atirador via, você ainda não tinha chegado à parede.

**Por que ficou de fora:** o jogo não tem ação contra outros jogadores. A coleta de orbes é decidida no presente do servidor.

### 2. Delta compression

**Problema:** cada snapshot envia o estado completo, mesmo o que não mudou. Os orbes, por exemplo, são reenviados 20 vezes por segundo sem mudar.

**Como seria:** o cliente confirma o último snapshot recebido. O servidor guarda os snapshots enviados para cada cliente e manda só a diferença em relação ao último confirmado: campos alterados, entidades que entraram e saíram. Se o cliente não confirmou nada recente, o servidor volta a mandar o estado completo. É a técnica do Quake 3 e de praticamente todos os FPS desde então.

**Ganho esperado:** os orbes (120 dos 234 bytes do exemplo do [ADR 0003](0003-protocolo-binario-e-json-de-controle.md)) quase desapareceriam do tráfego.

### 3. Interest management

**Problema:** todo cliente recebe todos os jogadores. Com centenas de jogadores, os snapshots ficariam enormes.

**Como seria:** cada cliente recebe só o que está perto dele ou visível (uma grade espacial, ou *area of interest*). É obrigatório em MMOs e battle royales.

### 4. Salas, escala e várias instâncias

**Problema:** existe um único mundo, com um único loop numa thread ([ADR 0008](0008-modelo-de-threads.md)).

**Como seria:** um `GameWorld` e um loop por sala, um serviço de *matchmaking* distribuindo jogadores, e as salas espalhadas por várias instâncias. Como a conexão WebSocket fica presa à instância que hospeda a sala, o roteamento precisa levar cada jogador ao servidor certo, por exemplo um endereço por sala ou um proxy com afinidade.

### 5. Reconexão e estado da sessão

**Problema:** se a conexão cai, o jogador é removido e volta como um jogador novo, com pontuação zero.

**Como seria:** um token de sessão no `welcome`. Ao reconectar com o mesmo token dentro de alguns segundos, o servidor devolve o mesmo jogador, e o cliente reinicia `seq` e `pending` a partir do ack recebido.

### 6. Autenticação e anti-trapaça

**Problema:** qualquer um conecta. O servidor autoritativo já impede trapaças de movimento (teletransporte, velocidade acima do limite), mas não impede bots externos jogando "perfeito", leitura do estado completo do mundo ("wallhack", trivial aqui porque todos recebem tudo) nem abuso de banda.

**Como seria:** autenticação no handshake do WebSocket, limite de taxa de mensagens por conexão, e interest management (item 3), que também impede o cliente de saber o que não deveria ver.

### 7. Suavização de correções

**Problema:** quando a reconciliação corrige a posição prevista, o personagem "pula" ([ADR 0006](0006-predicao-e-reconciliacao.md)).

**Como seria:** desenhar o personagem numa posição visual separada da posição lógica, aproximando uma da outra em alguns quadros. Aqui o pulo foi mantido visível de propósito.

### 8. Transporte UDP de verdade no navegador

**Problema:** o modo UDP é simulado sobre um WebSocket ([ADR 0002](0002-websocket-com-simulador-de-rede.md)).

**Como seria:** WebTransport (datagramas sobre HTTP/3 e QUIC) ou WebRTC DataChannel configurado sem ordem e sem retransmissão. O protocolo binário, os inputs redundantes e a interpolação continuariam iguais. Só a camada de transporte mudaria, que é justamente o motivo de as técnicas terem sido construídas para tolerar perda e reordenação.

### 9. Teste automatizado da física cruzada Java e JavaScript

**Problema:** a física existe em duas cópias ([ADR 0005](0005-fisica-deterministica-compartilhada.md)), e só a de Java tem testes automatizados.

**Como seria:** um teste que gera sequências aleatórias de inputs, roda as duas implementações (o JS via GraalJS ou Node) e compara as posições bit a bit.

## Consequências

- O código cabe numa leitura: ~800 linhas de Java (com imports) e ~400 de JavaScript.
- Cada técnica implementada pode ser ligada, desligada e medida no painel, o que não seria viável com todas as camadas acima.
- O projeto não deve ser usado como base de um jogo de produção sem pelo menos os itens 2, 4, 5 e 6.
