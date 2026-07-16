# Mission Control Clock — Android

Aplicativo Android nativo para exibir até oito horários simultâneos, inspirado no painel de relógios de um centro de controle.

## Recursos

- Até oito relógios configuráveis.
- Siglas de até três caracteres.
- Deslocamentos fixos de `UTC-12:00` a `UTC+14:00`, em intervalos de 15 minutos.
- Interface em inglês, português, espanhol, italiano e francês; inglês é o idioma inicial.
- Sincronização automática configurável por NTP, com fallback por HTTPS.
- Sincronização manual pelo menu ou por toque duplo.
- Tela vertical ou horizontal e modo imersivo.
- Opção de manter a tela ligada durante o uso.
- Configuração salva no aparelho.
- Toque longo na tela para abrir as configurações.

Ao atualizar da versão 1.1, os relógios, as siglas e os estados ativo/inativo são preservados. Os fusos baseados em cidades são convertidos uma única vez para o deslocamento UTC vigente no momento da atualização.

## APK

O GitHub Actions compila o APK de depuração automaticamente e o publica como artefato do workflow.
