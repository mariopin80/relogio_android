# Mission Control Clock — Android

Aplicativo Android nativo para exibir até oito horários simultâneos, inspirado no painel de relógios de um centro de controle.

## Recursos

- Até oito relógios configuráveis.
- Siglas de até três caracteres.
- Deslocamentos fixos de `UTC-12:00` a `UTC+14:00`, em intervalos de 15 minutos.
- Interface em inglês, português, espanhol, italiano e francês; inglês é o idioma inicial.
- Sincronização automática configurável por NTP, com fallback por HTTPS.
- Sincronização manual pelo menu ou por toque duplo.
- Rodapé técnico com fonte da hora, correção aplicada, RTT e horário da última sincronização.
- Tela vertical ou horizontal e modo imersivo.
- Opção de manter a tela ligada durante o uso.
- Layout automático, em uma coluna ou em duas colunas.
- Reordenação dos relógios e ajustes de tamanho do texto e espaçamento.
- Sigla e horário com o mesmo tamanho e alinhamento tipográfico.
- Data, dia da semana e dia do ano (DOY) configuráveis.
- Temas Mission Red, Terminal Green, Amber, Monochrome White e Night Red.
- Proteção opcional contra burn-in para telas OLED.
- Configuração salva no aparelho.
- Toque longo na tela para abrir as configurações.

Ao atualizar da versão 1.1, os relógios, as siglas e os estados ativo/inativo são preservados. Os fusos baseados em cidades são convertidos uma única vez para o deslocamento UTC vigente no momento da atualização.

## APK

O GitHub Actions compila o APK de depuração automaticamente e o publica como artefato do workflow.
