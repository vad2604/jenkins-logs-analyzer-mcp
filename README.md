# Jenkins Analyzer MCP Server

MCP-сервер на **Java / Spring Boot** для работы с **консольными логами Jenkins** из IDE или ассистента с поддержкой Model Context Protocol (MCP). Транспорт: **stdio** (процесс общается по stdin/stdout по протоколу MCP).

Сервер **не анализирует ваш репозиторий** — он скачивает лог сборки, ищет в нём строки с ошибками и фрагменты по запросу. Дальнейший разбор кода делает агент/разработчик.

## Возможности

- Загрузка полного лога или хвоста (`consoleText`)
- Поиск «error»-строк с контекстом (`get_error_lines_with_context`) — сканируется **весь** лог, опционально ограничивается число совпадений
- Поиск подстроки или regex в логе (`grep_in_logs`) — всегда сканируется весь лог, лимит только на число совпадений
- **Jenkins REST JSON API (без HTML):** `describe_jenkins_job_or_build` (имя job, номер сборки, статус, ветка по возможности) и `list_jenkins_job_builds` (последние сборки job)
- Кэширование логов завершённых сборок на диске; инструменты `get_log_cache_stats` и `clear_log_cache` для статистики и очистки кэша
- Обрезка очень длинных строк в ответах (по умолчанию первые 200 символов + `... [truncated]`, настраивается)
- Учётные данные Jenkins только через **переменные окружения** (не в аргументах tools)

## Документация для агента

Клиенты MCP могут показывать **инструкции сервера** из метаданных. Полный текст задаётся в `src/main/resources/application.yml` → `spring.ai.mcp.server.instructions` (на английском).

Краткая **матрица triage** (рекомендуемый порядок):

1. **`get_logs_tail`** — быстро посмотреть конец лога.
2. **`get_error_lines_with_context`** — эвристика ошибок по **всему** логу; при лимите сравнивайте `totalErrorMatches` и `returnedErrorMatches`.
3. **`grep_in_logs`** — поиск по **всему** логу (подстрока или regex); при лимите — `totalGrepMatches` и `returnedGrepMatches`.
4. **`get_full_logs`** — тяжёлый сценарий: только здесь режется **объём возвращаемых строк** через `maxLogLines` / `scanWholeLog`; для шагов 2–3 лог всё равно читается целиком.

Ошибки инструментов приходят в обёртке **`McpEnvelope`**: читайте `errorCode`, `agentMessage`, `technicalDetail`, `remediation`.

## Требования

- **JDK 21+** (`java -version`) — для локального запуска jar и сборки Maven
- **Maven 3.9+** — для `mvn package` на хосте (или только **Docker** — сборка внутри образа)
- **Docker** (опционально) — для запуска из контейнера
- Доступ к Jenkins по сети (VPN при необходимости)
- Учётная запись Jenkins с правом читать нужные job/build

## Быстрый старт

**Где искать jar:** после сборки запускаемый **fat-jar** лежит в каталоге **`dist/`** — файл **`dist/jenkins-analyzer-mcp.jar`**. Каталог **`target/`** содержит только промежуточные артефакты Maven; для `java -jar` используйте именно **`dist`**.

### 1. Клонировать репозиторий

```bash
git clone https://github.com/<org>/<repo>.git
cd <repo>
```

### 2. Собрать jar

```bash
mvn -DskipTests clean package
```

Команда создаёт каталог **`dist/`** (если его ещё нет) и записывает туда **`dist/jenkins-analyzer-mcp.jar`** — это и есть готовый fat-jar для запуска.

### 3. Проверить запуск вручную

```bash
export JENKINS_USER='your-user'
export JENKINS_TOKEN='your-api-token'
java -jar dist/jenkins-analyzer-mcp.jar
```

Процесс должен висеть без вывода в stdout (это нормально для stdio MCP). Логи сервера — в **stderr**; отдельный **файл** только если задан `LOG_FILE` (см. ниже).

## Аутентификация Jenkins

Параметры **не передаются** в вызовы инструментов — только через **окружение процесса**, в котором запущен `java -jar`:

| Переменная | Альтернатива |
|------------|--------------|
| `JENKINS_USER` | `JENKINS_USERNAME` |
| `JENKINS_TOKEN` | `JENKINS_API_TOKEN` |

Используется **HTTP Basic** (логин + API token Jenkins). После изменения переменных **перезапустите** MCP-сервер (процесс `java` или контейнер Docker).

## Запуск в Docker

Нужен **Docker Engine** (Docker Desktop / Docker CE). Образ собирает fat-jar внутри контейнера и запускает **тот же** процесс MCP по stdio (порт **не** слушается).

### Сборка образа

Из корня репозитория:

```bash
docker build -t jenkins-analyzer-mcp .
```

### Проверка вручную (stdio)

Флаг **`-i`** обязателен: MCP обменивается данными по stdin/stdout.

```bash
docker run -i --rm \
  -e JENKINS_USER='your-user' \
  -e JENKINS_TOKEN='your-api-token' \
  jenkins-analyzer-mcp
```

### Jenkins в сети Docker

- Если Jenkins доступен по обычному URL из интернета или корпоративной сети — дополнительных ничего не нужно.

- Если Jenkins на **хосте** (`localhost` в браузере), с контейнера `localhost` — это сам контейнер. На **Linux** удобно добавить:

  `--add-host=host.docker.internal:host-gateway`

  и в URL инструментов использовать хост, например `https://host.docker.internal:8080/...` (порт подставьте свой). На **Docker Desktop** (macOS/Windows) имя `host.docker.internal` обычно уже есть.

### docker compose (опционально)

В репозитории есть **`docker-compose.yml`**: сборка образа и том для кэша логов Jenkins (`~/.cache` внутри контейнера). Для проверки:

```bash
export JENKINS_USER='your-user'
export JENKINS_TOKEN='your-api-token'
docker compose build
docker compose run --rm -i jenkins-analyzer-mcp
```

Для **stdio MCP** в IDE надёжнее вызывать **`docker run -i`** (как ниже), а не долгоживущий `compose up`.

## Подключение MCP

1. Откройте настройки MCP (JSON); часто это файл конфигурации MCP в профиле пользователя.
2. Укажите **абсолютный путь** к **`dist/jenkins-analyzer-mcp.jar`** и при необходимости переменные окружения.

### Вариант: локальный `java -jar`

```json
{
  "mcpServers": {
    "jenkins-analyzer": {
      "command": "java",
      "args": [
        "-jar",
        "/ABSOLUTE/PATH/TO/jenkins-analyzer-mcp/dist/jenkins-analyzer-mcp.jar"
      ],
      "env": {
        "JENKINS_USER": "your-jenkins-login",
        "JENKINS_TOKEN": "your-jenkins-api-token",
        "LOG_FILE": "/ABSOLUTE/PATH/TO/jenkins-analyzer-mcp.log"
      }
    }
  }
}
```

Замените `ABSOLUTE/PATH/TO/...` на реальные пути на машине коллеги. **`LOG_FILE` не задавайте**, если файл лога не нужен — тогда логи только в **stderr** (файл на диске не создаётся).

### Вариант: Docker

Сначала соберите образ (`docker build -t jenkins-analyzer-mcp .`). В конфиге MCP передаёте **`docker run -i`** (интерактивный stdin), **`--rm`**, переменные Jenkins и имя образа. Значения `JENKINS_*` из блока `env` попадают в процесс `docker` и подставляются в контейнер через `-e`.

```json
{
  "mcpServers": {
    "jenkins-analyzer": {
      "command": "docker",
      "args": [
        "run",
        "-i",
        "--rm",
        "-e",
        "JENKINS_USER",
        "-e",
        "JENKINS_TOKEN",
        "jenkins-analyzer-mcp:latest"
      ],
      "env": {
        "JENKINS_USER": "your-jenkins-login",
        "JENKINS_TOKEN": "your-jenkins-api-token"
      }
    }
  }
}
```

При необходимости добавьте в `args` после `"--rm"` (на Linux, Jenkins на хосте): `"--add-host", "host.docker.internal:host-gateway"`.

### Другие клиенты (Claude Desktop, VS Code и т.д.)

Принцип тот же: **command** = `java` + `-jar` + путь к jar, либо **`docker`** + **`run -i`** + образ, **env** = переменные Jenkins. Для stdio MCP клиент должен **запускать процесс** с привязанным stdin/stdout, а не открывать URL.

## Формат URL Jenkins

Во все инструменты передаётся **`jenkinsUrl`**:

- страница job или **конкретного build** в Jenkins UI, **или**
- прямой URL вида `.../consoleText`

Для **логов** (`get_full_logs`, `get_logs_tail`, …) сервер сам нормализует ссылку к `.../consoleText`.

Для **API-инструментов** (`describe_jenkins_job_or_build`, `list_jenkins_job_builds`) нужен URL с путём **`/job/...`** (страница job или build в UI). Можно убрать хвосты вроде `/console`, `/consoleText` — сервер их отбрасывает.

## Инструменты (tools)

Ответ каждого инструмента обёрнут в **`McpEnvelope`**:

| Поле | Тип | Описание |
|------|-----|----------|
| `success` | boolean | Успех вызова |
| `data` | object | Результат при `success=true` |
| `errorCode`, `agentMessage`, `technicalDetail`, `remediation` | при ошибке | Подсказки для агента и человека |

### `get_full_logs`

| Параметр | Описание |
|----------|----------|
| `jenkinsUrl` | URL job/build или `consoleText` |
| `maxLogLines` | Опционально, лимит строк (по умолчанию 12000, 500..50000) |
| `scanWholeLog` | Если `true`, `maxLogLines` не применяется — возвращается весь лог (осторожно с размером) |

### `get_logs_tail`

| Параметр | Описание |
|----------|----------|
| `jenkinsUrl` | URL job/build или `consoleText` |
| `tailLines` | Сколько последних строк (по умолчанию 500, 1..10000) |

### `get_error_lines_with_context`

Всегда загружает **весь** лог и ищет строки с признаками ошибки (например `[ERROR]`, ` error `) с контекстом вокруг.

| Параметр | Описание |
|----------|----------|
| `jenkinsUrl` | URL job/build или `consoleText` |
| `maxErrorMatches` | Опционально, максимум совпадений в ответе (1..5000); без него — все совпадения |
| `contextLines` | Строк до/после каждой ошибки (по умолчанию 2, 0..20) |

В `data`: `totalLogLines`, `totalErrorMatches`, `returnedErrorMatches`, `matches[]`.

### `grep_in_logs`

Поиск по **всему** логу (как `get_error_lines_with_context`): объём лога не ограничивается отдельным параметром — только число возвращаемых совпадений.

| Параметр | Описание |
|----------|----------|
| `jenkinsUrl` | URL job/build или `consoleText` |
| `query` | Подстрока или шаблон regex (если `useRegex=true`) |
| `contextLines` | Контекст вокруг совпадения (0..20, по умолчанию 2) |
| `maxMatches` | Максимум совпадений в ответе (1..5000, по умолчанию 100) |
| `ignoreCase` | По умолчанию `true` (для regex — флаг `CASE_INSENSITIVE`) |
| `useRegex` | Если `true`, `query` компилируется как Java regex (`find()` по строке) |

В `data`: `totalLogLines`, `totalGrepMatches`, `returnedGrepMatches`, `useRegex`, `matches[]`. Если regex некорректен, вернётся ошибка с пояснением.

### `get_log_cache_stats`

Статистика по каталогу кэша на диске: путь, число файлов, суммарный размер. Не обращается к Jenkins.

### `clear_log_cache`

Удаляет все файлы `*.log` из каталога кэша. Следующая загрузка лога снова скачает его с Jenkins.

### `describe_jenkins_job_or_build`

Запрос к Jenkins **`.../api/json`** (JSON, не HTML): разбор URL, имя job, при наличии — номер сборки, `result` (например `SUCCESS`, `FAILURE`), `building`, время, **ветка** (из плагинов Git в ответе API или эвристика пути `.../job/<repo>/job/<branch>/...` для multibranch).

Если передан URL **job** без номера сборки, в ответе — метаданные job и при наличии **последней** сборки (`lastBuild`).

### `list_jenkins_job_builds`

Список последних сборок job (новые первыми) через API поля `builds`. Параметр **`limit`**: по умолчанию 20, максимум 100. Допускается URL конкретной сборки — job определяется автоматически.

## Обрезка длинных строк

Строки в ответах инструментов, если длиннее настроенного лимита, обрезаются до **первых N** символов и дополняются суффиксом `... [truncated]`. Поиск по логу внутри сервера выполняется по полным строкам.

Настройка:

- Свойство Spring: `analyzer.log-line-max-chars` (по умолчанию `200`), см. `application.yml`
- Переменная окружения: `ANALYZER_LOG_LINE_MAX_CHARS` (Spring Boot подхватывает как `analyzer.log-line-max-chars`)

## Кэш логов

- **Ключ:** SHA-256 от нормализованного URL `consoleText`
- **Каталог:** `.cache/jenkins-logs` (можно переопределить в `application.yml`: `analyzer.log-cache.dir`)
- **Запись на диск:** если в тексте лога есть признаки завершённой сборки (`Finished:`, и т.п.)
- **Очистка:** по TTL и по общему размеру кэша (см. `analyzer.log-cache` в `src/main/resources/application.yml`); вручную — инструмент `clear_log_cache`, статистика — `get_log_cache_stats`

## Логи самого MCP-сервера

| Куда | Описание |
|------|----------|
| Файл | Только если задан **`LOG_FILE`** (или `logging.file.name`) — rolling по размеру/дате |
| Переменная | `LOG_FILE` — полный путь к файлу; без неё файл **не создаётся** |
| Stdout | **Не используется** для логов (занят протоколом MCP) |
| Stderr | Логи приложения, если не пишете их в файл |

## Типичные проблемы

| Симптом | Что проверить |
|---------|----------------|
| HTTP 401/403 | Заданы ли `JENKINS_USER` / `JENKINS_TOKEN`, не истёк ли token, есть ли доступ к job |
| Неверный лог | URL указывает на нужный build, не на job без номера сборки, если нужен конкретный билд |
| `java` не найден | Установлен JDK 21+, `JAVA_HOME` / PATH |
| Пустой ответ в клиенте | Перезапустить MCP после смены `env` в конфиге |

## Разработка

```bash
mvn -DskipTests spring-boot:run
```

Готовый jar для распространения по-прежнему собирается через **`mvn package`** и попадает в **`dist/jenkins-analyzer-mcp.jar`**.

## Лицензия

Укажите лицензию проекта в файле `LICENSE` при публикации на GitHub.
