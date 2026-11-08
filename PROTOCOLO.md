# Memória Técnica: Protocolo do Royal Enfield Tripper Pod (Hunter 350)

> 🌐 *English specification available at [PROTOCOL.md](PROTOCOL.md).*

Documento de referência técnica com as descobertas, comportamentos de hardware e regras do protocolo Bluetooth Low Energy (BLE) validados na **Royal Enfield Hunter 350**.

---

## 1. Identificação de Hardware & Rede BLE

* **Fabricante do Chipset:** NXP Semiconductors
* **Prefixo de Endereço MAC:** `00:60:37` (Exemplo validado na Hunter 350: `00:60:37:D2:35:85`)
* **Nome BLE Anunciado:** `RE_DISP`
* **Tecnologia:** BLE puro (Bluetooth Low Energy / BR-EDR não suportado).
* **Janela de Anúncio (*Advertising Window*):**
  * O painel só anuncia sua presença por **menos de 30 segundos** após ligar a ignição.
  * Se nenhum dispositivo conectar nesse intervalo, o rádio BLE desliga para poupar bateria. É necessário desligar e ligar a chave novamente para reabrir a janela.
  * Quando a conexão é encerrada pelo próprio Pod (queda por watchdog), ele volta a anunciar imediatamente.

---

## 2. Estrutura de Serviços GATT (NXP Wireless UART)

* **Service UUID:** `01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0`
* **Characteristic UUID:** `01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0`
* **Propriedades da Característica:** `WRITE NO RESPONSE`
  * O canal **não possui** a propriedade padrão `NOTIFY`.
* **Canal Reverso Obrigatório (Pod → Celular): Dual-Role GATT**
  * O Tripper Pod **não possui** suporte a notificações (`NOTIFY`) em sua característica BLE.
  * O Pod implementa uma arquitetura *dual-role*: além de ser um servidor GATT ao qual o celular conecta, ele age ativamente como um *cliente GATT* que procura um serviço idêntico (`01FF0100...`) hospedado no celular e tenta escrever dados de retorno.
  * **Causa do fracasso no Web Bluetooth (Google Chrome / Web BLE):** A API Web Bluetooth do navegador suporta apenas ser cliente GATT, sendo incapaz de instanciar um `GATT Server` local. Ao não encontrar o canal reverso no celular, o firmware do Tripper Pod encerra a conexão por timeout após ~1 a 2 segundos.
  * **Solução com Sucesso no APK Nativo Android:** O aplicativo nativo executa `BluetoothManager.openGattServer()` criando o serviço NXP local com permissão de escrita antes de se conectar ao Pod, mantendo a conexão 100% estável.

---

## 3. Formato e Integridade dos Pacotes

* **Tamanho Fixo:** Exatamente **20 bytes** por pacote (18 bytes de payload + 2 bytes de CRC).
* **Algoritmo de Integridade:** `CRC-16/CCITT-FALSE`
  * **Polinômio:** `0x1021`
  * **Valor Inicial:** `0xFFFF`
  * **Ordem:** MSB-first (Big-Endian nos bytes `[18..19]`)
  * **XOR Final:** Nenhum (`0x0000`)
  * **Cálculo:** Aplicado estritamente sobre os primeiros 18 bytes (`[0..17]`).

---

## 4. Temporização, Regras Anti-Queda & Watchdogs

### A. Tempo Anti-Rejeição Pós-Conexão (Essencial)
* **Regra:** Aguardar de **200 ms a 300 ms** imediatamente após a conexão física e descoberta dos serviços antes de transmitir o primeiro byte.
* **Motivo:** O microcontrolador da NXP precisa desse intervalo para estabilizar o stack BLE. Transmissões imediatas resultam em desconexão instantânea pelo firmware.

### B. Serialização de Escrita
* **Regra:** Nunca enviar pacotes simultâneos. Manter uma fila estrita e aguardar um intervalo de **50 ms a 60 ms** entre envios sucessivos.

### C. Watchdog Geral de Firmware (Keepalive)
* **Regra:** O Tripper Pod encerra a conexão (`status=19`) se ficar aproximadamente **5 segundos** sem receber dados válidos.
* **Solução:** Enviar o pacote de sinal de vida (`PING_FW`) a cada **1,5 a 2,0 segundos**.

### D. Watchdog de Telas e Manobras (Crítico)
* **Regra:** Quando uma tela de manobra (`0x10 0x11`) ou bússola está ativa, o `PING_FW` genérico **não** é suficiente para renovar o watchdog da tela.
* **Comportamento:** O Pod interpreta a falta de atualização da manobra como congelamento do GPS e derruba o link após ~4 a 5 segundos.
* **Solução:** O loop de keepalive deve **reenviar continuamente o próprio pacote da manobra ativa** a cada 1,5 segundos.

---

## 5. Dicionário de Pacotes Principais (Hexadecimal)

### 5.1 Pacotes Fixos de Sistema
| Função | Hexadecimal Completo (20 bytes com CRC) |
|---|---|
| **Handshake Novo (Pedir PIN)** | `21 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 50 A7` |
| **Handshake Reconexão (Já Pareado)** | `21 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 40 45` |
| **Keepalive Genérico (`PING_FW`)** | `03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 45 D9` |
| **Overlay de Chamada Telefônica** | `40 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00` + CRC |

### 5.2 Confirmação de PIN (`0x20`)
* `[0]`: `0x20`
* `[1..6]`: PIN digitado em caracteres ASCII (ex: `"123456"` = `0x31 0x32 0x33 0x34 0x35 0x36`)
* `[7..17]`: `0x00`
* `[18..19]`: CRC-16

### 5.3 Sincronização do Relógio (`0x50`)
* `[0]`: `0x50`
* `[1]`: Hora (formato 24h: `0` a `23`)
* `[2]`: Minuto (`0` a `59`)
* `[3..17]`: `0x00`
* `[18..19]`: CRC-16

### 5.4 Pacote de Navegação e Manobras (`CMD_NAVIGATE` - `0x10 0x11`)
```text
Byte [0]    : 0x10
Byte [1]    : 0x11
Byte [2]    : Código do ícone principal (grande)
Byte [3..4] : Distância da manobra (uint16 big-endian)
Byte [5]    : Unidade (0x01 = metros até 999m; 0x02 = km x 10 acima de 999m)
Byte [6]    : Tema/Intensidade (0x00 = dia, 0x01 = noite)
Byte [7]    : Código da próxima manobra (seta pequena; 0xFF = sem dado)
Byte [8..9] : Distância secundária / radar (0xFF 0xFF = sem radar)
Byte [10]   : 0x41 (fixo)
Byte [11..13]: Linha inferior (distância total / hora de chegada; 0xFF 0xFF 0xFF = vazio)
Byte [14..17]: 0x00 (zeros)
Byte [18..19]: CRC-16
```

### 5.5 Catálogo Completo de Ícones Conhecidos & Áreas de Exploração (Byte `[2]`)

| Faixa / Código Hex | Significado Mapeado | Status de Teste |
|---|---|---|
| `0x00` | Tela Limpa / Sem ícone | Documentado |
| `0x01` | Destino alcançado / Bandeira quadriculada | Documentado |
| `0x02` | Início de rota / Ponto de partida | Documentado |
| `0x03..0x08` | Variações de partida / Manobras preliminares | Não testado |
| `0x09` | Seguir em frente (Seta reta para cima) | **Confirmado na Hunter 350** |
| `0x0A` | Seguir em frente (Variação de pista) | Documentado |
| `0x0B` | Rotatória - 1ª Saída | **Confirmado na Hunter 350** |
| `0x0C` | Rotatória - 2ª Saída | **Confirmado na Hunter 350** |
| `0x0D` | Rotatória - 3ª Saída | **Confirmado na Hunter 350** |
| `0x0E` | Rotatória - 4ª Saída | **Confirmado na Hunter 350** |
| `0x0F` | Rotatória - 5ª Saída | **Confirmado na Hunter 350** |
| `0x10` | Rotatória - 6ª Saída | **Confirmado na Hunter 350** |
| `0x11` | Rotatória - 7ª Saída | **Confirmado na Hunter 350** |
| `0x12` | Rotatória - 8ª Saída | **Confirmado na Hunter 350** |
| `0x13` | Rotatória - 9ª Saída | **Confirmado na Hunter 350** |
| `0x14` | Virar à esquerda (90°) | **Confirmado na Hunter 350** |
| `0x15` | Virar à direita (90°) | **Confirmado na Hunter 350** |
| `0x16` | Curva fechada à esquerda (Sharp Left) | Documentado |
| `0x17` | Curva fechada à direita (Sharp Right) | Documentado |
| `0x18` | Curva suave à esquerda / Mantenha à esquerda | Documentado |
| `0x19` | Curva suave à direita / Mantenha à direita | Documentado |
| `0x1A` | Saída de rodovia à esquerda | Documentado |
| `0x1B` | Saída de rodovia à direita | Documentado |
| `0x1C` | Bifurcação à esquerda | Documentado |
| `0x1D` | Bifurcação à direita | Documentado |
| `0x1E` | Mantenha o centro da pista | Documentado |
| `0x1F..0x22` | Faixa não catalogada | 🔍 **Alvo de Exploração** |
| `0x23` | Cruzamento / Alerta de pedestres | Suspeito |
| `0x24..0x27` | Faixa não catalogada | 🔍 **Alvo de Exploração** |
| `0x28` | Alerta de radar / Fiscalização | Suspeito |
| `0x29..0x31` | Faixa não catalogada | 🔍 **Alvo de Exploração** |
| `0x32` | Rotatória - 1ª Saída | **Confirmado na Hunter 350** |
| `0x33` | Rotatória - 2ª Saída | Documentado |
| `0x34` | Rotatória - 3ª Saída | Documentado |
| `0x35` | Rotatória - 4ª Saída | Documentado |
| `0x36` | Rotatória - 5ª Saída | Documentado |
| `0x37` | Rotatória - 6ª Saída | Documentado |
| `0x38` | Rotatória - 7ª Saída | Documentado |
| `0x39..0x3C` | Rotatória variações / Saídas adicionais | Documentado |
| `0x3D` | Retorno (U-Turn) | Documentado |
| `0x3E` | Retorno à direita (U-Turn inverso) | Documentado |
| `0x3F` | Faixa não catalogada | 🔍 **Alvo de Exploração** |
| `0x40` | Alerta geral / Perigo na pista | Suspeito |
| `0x41` | Tela cheia de Bússola (orientação no byte `[14]`) | Documentado |
| `0x42` | Alerta de perda de conexão móvel (Sem GPS / Sem Internet) | Documentado |
| `0x43` | Alerta de limite de velocidade excedido | Suspeito |
| `0x44` | Alerta de bateria fraca do smartphone | Documentado |
| `0x45` | Chamada recebida (Overlay) | Documentado |
| `0x46..0xFF` | Faixa estendida de firmware NXP | 🔍 **Alvo de Exploração Aberto** |

---

## 6. Limites de Customização do Tripper Pod

Compreender o que é e o que não é fisicamente possível alterar no painel é fundamental para orientar o desenvolvimento:

### O que o Painel FAZ (Controle Total por Software):
1. **Renderização de Ícones Vetoriais Pré-Gravados na ROM:** Qualquer um dos 256 índices (`0x00` a `0xFF`) pode ser invocado e exibido no centro da tela.
2. **Campos Numéricos e Texto Dinâmico:**
   * Distância primária da manobra (metros ou décimos de km).
   * Distância secundária / radar.
   * Linha inferior de status (hora prevista de chegada ETA ou distância total restante).
3. **Controle de Iluminação e Contraste:** Alternância entre modo Dia (fundo claro/alto brilho) e modo Noite (fundo escuro).
4. **Sincronização de Relógio:** Acerto preciso de horas e minutos (`0x50`).
5. **Telas Especiais:** Modo bússola digital (`0x41`) e alertas de sistema.

### O que o Painel NÃO FAZ (Limitações Físicas do Firmware Original):
1. **Envio de Imagens Arbitrárias / Bitmaps (Ex: Fotos, Logos customizados):**
   * O display do Tripper Pod é acionado por um microcontrolador NXP que possui uma tabela fixa de glifos/ícones pré-compilados na memória flash. O protocolo BLE de 20 bytes **não possui** largura de banda nem comando conhecido para transferir buffers de pixels brutos (framebuffer).
2. **Mudança de Tipografia / Fontes:** As fontes dos números e unidades são renderizadas internamente pelo firmware.
3. **Modificação do Firmware Interno via BLE:** Não é seguro tentar flashar um novo firmware por BLE sem o bootloader oficial assinado da Royal Enfield / NXP, sob risco de inutilizar (*brickar*) o módulo.

---

## 7. Metodologia de Exploração de Ícones (Fuzzing)

O OpenTripper implementa a partir da versão **v0.3.1** um **Explorador Interativo de Ícones** nativo:
1. Ao conectar à moto, a interface permite enviar sequencialmente qualquer código hexadecimal de `0x00` a `0xFF`.
2. Para cada código, o pacote de navegação é montado e mantido no loop contínuo de keepalive (1,5s), permitindo ao piloto observar o visor com calma.
3. Botões de salto rápido (`Pular p/ Ocultos`) avançam diretamente para os blocos não documentados (`0x0B`, `0x12`, `0x23`, `0x28`, `0x40`, `0x46`, etc.).
4. O botão **"📝 Anotar O Que Apareceu"** abre um formulário que grava imediatamente a descrição do que surgiu no painel no arquivo persistente `opentripper_logs.txt`.

---

## 8. Mapeamento de Manobras de Navegação (Google Maps & Waze)

O OpenTripper converte em tempo real as notificações textuais emitidas pelos aplicativos de navegação para os opcodes vetoriais do hardware do Tripper Pod através do parser de heurísticas (`TurnParser`):

| Padrão Detectado no Texto da Notificação | Código de Manobra Hex | Ícone no Tripper Pod |
|---|---|---|
| `"rotatória"`, `"rotatoria"`, `"roundabout"` + `"1ª"` / `"1st"` / `"primeira"` | `0x0B` | Rotatória 1ª Saída |
| `"rotatória"`, `"rotatoria"`, `"roundabout"` + `"2ª"` / `"2nd"` / `"segunda"` | `0x0C` | Rotatória 2ª Saída |
| `"rotatória"`, `"rotatoria"`, `"roundabout"` + `"3ª"` / `"3rd"` / `"terceira"` | `0x0D` | Rotatória 3ª Saída |
| `"rotatória"`, `"rotatoria"`, `"roundabout"` + `"4ª"` / `"4th"` / `"quarta"` | `0x0E` | Rotatória 4ª Saída |
| `"rotatória"`, `"rotatoria"`, `"roundabout"` + `"5ª"` / `"5th"` / `"quinta"` | `0x0F` | Rotatória 5ª Saída |
| `"rotatória"`, `"rotatoria"`, `"roundabout"` + `"6ª"` / `"6th"` / `"sexta"` | `0x10` | Rotatória 6ª Saída |
| `"rotatória"`, `"rotatoria"`, `"roundabout"` + `"7ª"` / `"7th"` / `"sétima"` | `0x11` | Rotatória 7ª Saída |
| `"rotatória"`, `"rotatoria"`, `"roundabout"` + `"8ª"` / `"8th"` / `"oitava"` | `0x12` | Rotatória 8ª Saída |
| `"rotatória"`, `"rotatoria"`, `"roundabout"` + `"9ª"` / `"9th"` / `"nona"` | `0x13` | Rotatória 9ª Saída |
| `"retorno"`, `"u-turn"` | `0x3D` | Retorno (U-Turn) |
| `"curva fechada à esquerda"`, `"sharp left"` | `0x16` | Curva fechada à esquerda |
| `"curva fechada à direita"`, `"sharp right"` | `0x17` | Curva fechada à direita |
| `"curva suave à esquerda"`, `"slight left"`, `"mantenha-se à esquerda"` | `0x18` | Curva suave à esquerda |
| `"curva suave à direita"`, `"slight right"`, `"mantenha-se à direita"` | `0x19` | Curva suave à direita |
| `"vire à esquerda"`, `"esquerda"`, `"left"` | `0x14` | Virar à esquerda (90°) |
| `"vire à direita"`, `"direita"`, `"right"` | `0x15` | Virar à direita (90°) |
| `"saída"`, `"saida"`, `"exit"` + `"esquerda"` | `0x1A` | Saída de rodovia à esquerda |
| `"saída"`, `"saida"`, `"exit"` + `"direita"` | `0x1B` | Saída de rodovia à direita |
| `"bifurcação"`, `"bifurcacao"`, `"fork"` + `"esquerda"` | `0x1C` | Bifurcação à esquerda |
| `"bifurcação"`, `"bifurcacao"`, `"fork"` + `"direita"` | `0x1D` | Bifurcação à direita |
| `"destino"`, `"chegando"`, `"chegou"` | `0x01` | Destino alcançado |
| `"siga"`, `"continue"`, `"em frente"` | `0x09` | Seguir em frente |

### Formatação de Distâncias no Barramento (`Bytes [3..5]`):
* **Valores em Metros (até 999m):** `Bytes [3..4] = valor uint16`, `Byte [5] = 0x01`. Ex: `250 m` ➔ `0x00 0xFA 0x01`.
* **Valores em Quilômetros (>= 1,0 km):** Convertido para décimos de quilômetro (`km * 10`). `Bytes [3..4] = uint16`, `Byte [5] = 0x02`. Ex: `3,5 km` ➔ `35` decikm ➔ `0x00 0x23 0x02`.
