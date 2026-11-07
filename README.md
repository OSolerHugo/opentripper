# OpenTripper 🏍️

Investigação, diagnóstico e customização do Royal Enfield Tripper Pod para a **Hunter 350**.

## Sobre o Projeto
O OpenTripper é uma iniciativa open source para engenharia reversa, análise de protocolo e desenvolvimento de interfaces/aplicativos alternativos para o painel de navegação Tripper Pod da Royal Enfield Hunter 350.

## OpenTripper Web (Web Bluetooth)
Interface web desenvolvida em HTML/JavaScript puro para diagnóstico e controle direto via Google Chrome (Android) com Web Bluetooth API.

- Reconhecimento automático do serviço NXP Wireless UART (`01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0`)
- Keepalive contínuo automático (evita timeout de 5s do watchdog do firmware)
- Solicitação e pareamento por PIN
- Sincronização do relógio analógico/digital nativo
- Teste de telas e ícones de manobra (curvas, retas, rotatórias)
- Modo bússola e overlay de chamadas

## Licença
MIT
