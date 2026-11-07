# Memória Técnica: Protocolo do Royal Enfield Tripper Pod (Hunter 350)

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
  * **Canal Reverso (Pod → Celular):** No fluxo nativo, o aplicativo hospeda um `GATT Server` local com o mesmo UUID, e o Tripper Pod escreve de volta no celular dentro da mesma conexão BLE.

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

### 5.5 Códigos de Ícones Comuns (Byte `[2]`)
* `0x09`: Seguir em frente (reta)
* `0x14`: Virar à esquerda (90°)
* `0x15`: Virar à direita (90°)
* `0x16` / `0x17`: Curva acentuada esquerda / direita
* `0x18` / `0x19`: Curva suave / mantenha esquerda / direita
* `0x32`: Rotatória saída 1 (soma `+ (saída - 1)`)
* `0x3D`: Retorno (U-Turn)
* `0x41`: Modo tela cheia de Bússola (direção no byte `[14]`)
* `0x42`: Alerta de perda de dados móveis
* `0x44`: Alerta de bateria fraca
