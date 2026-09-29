# MogRitual — «Я тебя могну»

Лёгкий ритуальный плагин для Leaf / Paper 1.21.11, Java 21.

## Основная логика

1. Два разных игрока пишут `я тебя могну` — регистр букв не важен.
2. После первого игрока второй должен присоединиться в течение 12 секунд; радиус по умолчанию 12 блоков.
3. После второго запускается 4-секундный ritual.
4. Затем запускается 2-секундная roulette-анимация.
5. По умолчанию каждый из двух получает свою награду.
6. Cooldown ставится только после успешного завершения.

## Производительность

Плагин не использует ArmorStand, ItemDisplay, TextDisplay или NPC. Во время ritual создаётся только 3 BlockDisplay на участника (максимум 6 для двух игроков), после чего они удаляются.

Во время ritual работает один повторяющийся Bukkit task. После него он отменяется и запускается один roulette task. После roulette второй task тоже отменяется.

Визуал строится из:
- реальный dance игроков: присед + поочерёдные взмахи руками;
- 3 вращающихся BlockDisplay вокруг каждого игрока;
- END_ROD / ELECTRIC_SPARK / ENCHANT / WITCH;
- actionbar `♪ Я тебя могну ♪`;
- titles и sounds.

## Удобный config.yml

Основные секции:

- `general` — быстро включить/выключить trigger;
- `trigger` — фраза, число игроков, радиусы, миры, permission, обработка регистра/пробелов/знаков;
- `cooldown` — длительность, PLAYER/GLOBAL, SUCCESS/START, persistence, bypass;
- `ritual` — длительность, dance, 3 orbiting blocks, particles, titles и custom/vanilla sound;
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
1. ritual двумя реальными игроками, включая 12-секундный timeout и chat-controller;
2. TPS/MSPT;
3. фактические команды серверных kit/case/economy плагинов;
4. custom sound key, если сервер использует resource pack.


## Chat-controller

Версия 1.3 по умолчанию принимает cancelled Paper chat events (`trigger.accept-cancelled-chat: true`). При обновлении старого config-version 2 плагин один раз мигрирует старый config до version 4, включает chat-controller compatibility и добавляет dance-настройки автоматически.


## Dance 1.2

Во время ритуала реальные модели участников чередуют crouch и arm swing. Вокруг каждого участника вращаются ровно 3 блока. Материалы, радиус, высота, bobbing, скорость, glow, частицы и actionbar настраиваются в `ritual.dance`.

Для настоящего трека используется существующий `ritual.sounds.custom-key` из server resource pack. Если custom-key пустой, играет лёгкий vanilla note-block fallback.


## Cinematic 1.3

Ритуал длится 7 секунд и синхронизирован с отдельным resource pack sound `mogritual:ya_tebya_mognu`.

Каждый участник получает свою независимую третьелицевую cinematic-камеру. Камера плавно облетает настоящую модель игрока; игрок при этом вращается, приседает и машет руками. Вокруг модели остаются ровно три видимых BlockDisplay.

Для camera packet используется Mojang-mapped NMS `ClientboundSetCameraPacket`. После успеха, отмены или отключения плагина камера возвращается на самого игрока, а временные camera/display entities удаляются.

Красивый текст строится как RGB-gradient Component из `ritual.dance.text.*`.

Отдельный resource pack содержит только подготовленный пользователем аудиофрагмент 0:07–0:14 и не хранится в публичном исходном коде репозитория.
