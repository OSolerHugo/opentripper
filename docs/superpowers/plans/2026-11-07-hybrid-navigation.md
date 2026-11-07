# Plano de Implementação: Modo Híbrido (Navegação & Velocímetro) - OpenTripper

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transformar o OpenTripper em um aplicativo completo para motociclistas com Modo Híbrido (Navegação via Google Maps/Waze e Velocímetro Digital via GPS com economia agressiva de bateria).

**Architecture:** Arquitetura desacoplada e orientada a eventos. O `TurnByTurnService` intercepta notificações do Maps/Waze, o `BatteryAwareLocationProvider` fornece velocidade/rumo sob demanda com desligamento instantâneo pós-desconexão, e o `DisplayCoordinator` arbitra a prioridade e monta os pacotes para o `TripperBridge`.

**Tech Stack:** Java, Android Framework puro (API 26+), BLE GATT Dual-Role, Android `NotificationListenerService`, `LocationManager`.

**Spec:** `docs/superpowers/specs/2026-11-07-hybrid-navigation-design.md`

## Global Constraints
- Zero dependências pesadas de terceiros (manter o APK ultra-leve abaixo de 50 KB).
- O GPS não pode ficar ativo se a moto estiver desconectada.
- O loop de keepalive do Tripper Pod deve ser de 1,5s com retransmissão da tela ativa.
- Manter o suporte nativo a todas as saídas de rotatória descobertas (`0x0B..0x13`).

---

### Task 1: Provedor de Localização e Velocímetro com Economia de Bateria

**Files:**
- Create: `app/src/main/java/com/opentripper/BatteryAwareLocationProvider.java`
- Test: `app/src/test/java/com/opentripper/LocationFormatTest.java`

**Interfaces:**
- Produces: 
  - `start()` e `stop()`
  - Callback: `onSpeedUpdated(int speedKmH, float bearing, float tripDistanceKm)`

- [ ] **Passo 1: Escrever teste de unidade para conversão de velocidade e formatação de pacotes**
- [ ] **Passo 2: Implementar `BatteryAwareLocationProvider` com `LocationManager` nativo**
  - Registrar listener com `minTimeMs = 1000` e `minDistanceM = 0`.
  - Converter `location.getSpeed() * 3.6f` para km/h.
  - Acumular distância parcial da sessão.
  - Garantir que `stop()` remove imediatamente todos os listeners do sistema.
- [ ] **Passo 3: Rodar testes de unidade e validar**

---

### Task 2: Interceptador de Navegação Turn-by-Turn (Google Maps & Waze)

**Files:**
- Create: `app/src/main/java/com/opentripper/TurnByTurnService.java`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/opentripper/NavigationParserTest.java`

**Interfaces:**
- Produces:
  - Evento: `NavStep(int iconCode, int distanceValue, int distanceUnit, String eta)`
  - Evento: `onNavigationEnded()`

- [ ] **Passo 1: Escrever teste de unidade para parsing de texto do Google Maps e Waze**
  - Testar frases: "Em 300 m vire à esquerda", "Em 1,5 km pegue a 2ª saída da rotatória", "Siga em frente".
- [ ] **Passo 2: Criar classe `TurnByTurnService` estendendo `NotificationListenerService`**
  - Filtrar pacotes: `com.google.android.apps.maps` e `com.waze`.
  - Extrair dados de texto e ícones das notificações.
  - Mapear termos de rotatórias (1ª a 9ª saída) diretamente para `0x0B..0x13`.
- [ ] **Passo 3: Declarar permissão `BIND_NOTIFICATION_LISTENER_SERVICE` no `AndroidManifest.xml`**
- [ ] **Passo 4: Rodar testes e validar**

---

### Task 3: Coordenador Híbrido de Telas (`DisplayCoordinator`)

**Files:**
- Create: `app/src/main/java/com/opentripper/DisplayCoordinator.java`
- Modify: `app/src/main/java/com/opentripper/TripperProtocol.java`

**Interfaces:**
- Consumes: `NavStep` de `TurnByTurnService` e dados de velocidade de `BatteryAwareLocationProvider`.
- Produces: Pacotes de 20 bytes formatados (`0x10 0x11`) enviados para o `TripperBridge`.

- [ ] **Passo 1: Implementar máquina de estados**
  - `STATE_NAVIGATING`: se houver notificação ativa há menos de 10s.
  - `STATE_CRUISING`: se não houver notificação ativa (exibe velocímetro ou bússola).
- [ ] **Passo 2: Construir pacote de Velocímetro no `TripperProtocol`**
  - Método `buildSpeedometerPacket(int speedKmH, float tripDistanceKm)`.
- [ ] **Passo 3: Gerenciar o timer de watchdog unificado de 1,5s**

---

### Task 4: Serviço de Segundo Plano e Auto-Conexão de Baixo Consumo

**Files:**
- Create: `app/src/main/java/com/opentripper/TripperService.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Gerencia o ciclo de vida da conexão BLE mesmo com a tela do celular apagada no bolso.

- [ ] **Passo 1: Criar `TripperService` como Foreground Service**
  - Notificação de status discreta: *"OpenTripper conectado à Hunter 350"*.
- [ ] **Passo 2: Implementar varredura BLE de baixo consumo para auto-conexão**
  - `ScanSettings.SCAN_MODE_LOW_POWER` com `ScanFilter` filtrando estritamente por nome `RE_DISP`.
- [ ] **Passo 3: Desligamento automático imediato do GPS ao detectar desconexão da moto**

---

### Task 5: Redesenho da Interface do Piloto (`MainActivity`)

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/opentripper/MainActivity.java`

- [ ] **Passo 1: Criar Card de "Espelho do Visor" em tempo real**
  - Mostra visualmente a velocidade atual ou a manobra ativa do Waze.
- [ ] **Passo 2: Adicionar interruptores de configuração**
  - Toggle `Auto-conectar ao ligar a moto` (salvo em `SharedPreferences`).
  - Seletor de Modo Cruzeiro: `Velocímetro Digital` vs `Bússola 360°`.
  - Indicador e atalho para habilitar permissão de leitura de notificações.
- [ ] **Passo 3: Mover o Explorador de Ícones e Console de Logs para seção retrátil/aba secundária**

---

### Task 6: Verificação de Build, Testes e Lançamento v0.4.0

**Files:**
- Modify: `app/build.gradle` (bumping to `versionCode 3`, `versionName "0.4.0"`)
- Modify: `.github/workflows/build-apk.yml`

- [ ] **Passo 1: Rodar build e testes locais**
- [ ] **Passo 2: Commit e push para acionar GitHub Actions**
- [ ] **Passo 3: Validar a publicação da Release v0.4.0 no GitHub**
