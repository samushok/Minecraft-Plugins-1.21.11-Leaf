SantaProbe.java — интеграционная проверка для отдельного локального сервера Leaf 1.21.11.
НЕ устанавливать на рабочий сервер: создаёт тестовых игроков и завершает сервер после теста.
Нужны зависимости ядра, как для основного build.py. Main: SantaProbe; depend: [Santaball, SummerBall].
Manifest должен содержать paperweight-mappings-namespace: mojang.
