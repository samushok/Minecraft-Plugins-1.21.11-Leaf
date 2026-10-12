# Signal67

Плагин для **Leaf / Paper 1.21.11 + Java 21**.

Игрок пишет в чат ровно `67`. Сообщение скрывается, вокруг игрока запускается семиканальный визуальный "Сигнал 67", рулетка постепенно замедляется и фиксирует награду.

## Почему это не копия старой рулетки
- не нужно строить структуру;
- нет NPC/Citizens/ProtocolLib;
- триггер — обычное сообщение `67`;
- семь TextDisplay-секторов вращаются вокруг игрока;
- награды мелькают в title и фиксируются в конце;
- примерно 7 секунд визуала с небольшим числом частиц.

## Механика
- cooldown: 1 час;
- cooldown хранится в PDC игрока и переживает рестарт;
- ловит Paper AsyncChatEvent даже если сообщение уже отменено другим chat plugin;
- weighted rewards в config.yml;
- console commands с %player% и %uuid%;
- максимум одновременных ритуалов настраивается;
- cleanup временных TextDisplay;
- /signal67 status
- /signal67 reload
- /signal67 reset <player>
- /signal67 test [player]

## Шар Шторма
В config есть выключенная награда storm_ball. Она намеренно не активирована, пока не указана настоящая команда выдачи твоего Storm Ball.

## Сборка
```bash
cd source/Signal67
gradle clean build
```

JAR:
`build/libs/Signal67-1.0-Leaf-1.21.11.jar`
