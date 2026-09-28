# MogRitual — «Я тебя могну»

Лёгкий ритуальный плагин для Leaf / Paper 1.21.11, Java 21.

## Основная логика

1. Три разных игрока пишут `я тебя могну`.
2. По умолчанию у них 20 секунд и радиус 12 блоков.
3. После третьего запускается 4-секундный ritual.
4. Затем запускается 2-секундная roulette-анимация.
5. По умолчанию каждый из трёх получает свою награду.
6. Cooldown ставится только после успешного завершения.

## Производительность

Плагин специально не использует ArmorStand, BlockDisplay, ItemDisplay, TextDisplay или NPC.

Во время ritual работает один повторяющийся Bukkit task. После него он отменяется и запускается один roulette task. После roulette второй task тоже отменяется.

Визуал строится из:
- END_ROD ring;
- ENCHANT в центре;
- WITCH около участников;
- titles;
- sounds.

## Удобный config.yml

Основные секции:

- `general` — быстро включить/выключить trigger;
- `trigger` — фраза, число игроков, радиусы, миры, permission, обработка регистра/пробелов/знаков;
- `cooldown` — длительность, PLAYER/GLOBAL, SUCCESS/START, persistence, bypass;
- `ritual` — длительность, titles, particles, custom/vanilla sound;
- `roulette` — EACH/ONE_RANDOM, preview/final sounds, titles, broadcast, dry-run;
- `chance-command` — формат `/mogchance`;
- `messages` — все основные сообщения;
- `rewards` — enabled/display-name/weight/chance/commands для каждой награды.

## Cooldown

По умолчанию:

`scope: PLAYER`

Каждый участник получает отдельный cooldown.

`start: SUCCESS`

Если ritual отменился, cooldown не тратится.

Для админского тестирования permission `mogritual.cooldown.bypass` по умолчанию доступен OP.

## Custom sound

Сам JAR не содержит аудиофайл.

Если resource pack содержит нужный звук, укажите:

`ritual.sounds.custom-key: "custom.ya_tebya_mognu"`

При непустом custom-key vanilla fallback по умолчанию отключается, чтобы звуки не накладывались.

## Roulette

`reward-mode: EACH`
— каждый участник получает отдельную прокрутку.

`reward-mode: ONE_RANDOM`
— награду получает один случайный участник.

Для безопасного тестирования:

`execute-reward-commands: false`

Тогда анимация и результаты будут показаны, но реальные команды наград не выполнятся.

## Награды

Каждая награда имеет:

- `enabled`
- `display-name`
- `weight` — реальный вес выбора
- `chance` — отдельное display-значение для `/mogchance`
- `commands`

Команды:
- `CONSOLE:<команда>`
- `PLAYER:<команда>`

Плейсхолдеры:
- `%player%`
- `%uuid%`
- `%reward%`

`/mogchance` может одновременно показать display chance, weight и фактический процент, рассчитанный по всем активным weight.

## Команды

- `/mogritual status`
- `/mogritual reload`
- `/mogritual validate`
- `/mogritual resetcooldown <player|all>`
- `/mogritual clearpending`
- `/mogchance`

## Перед production

Нужно проверить:
1. ritual тремя реальными игроками;
2. TPS/MSPT;
3. фактические команды серверных kit/case/economy плагинов;
4. custom sound key, если сервер использует resource pack.
