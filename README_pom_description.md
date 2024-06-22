# Проект SearchEngine

Описание зависимостей в pom.xml.

## Зависимости

### Spring Boot Starter Parent
Parent POM для Spring Boot приложений. Управляет версиями зависимостей и предоставляет базовую конфигурацию.

### Spring Boot Starter Web
Включает необходимые библиотеки для разработки веб-приложений на Spring, включая веб-контроллеры и HTTP клиенты.

### Spring Boot Starter Thymeleaf
Интеграция с Thymeleaf, шаблонизатором для создания веб-интерфейсов в Spring приложениях.

### Lombok
Уменьшает объем кода за счет автоматической генерации методов, таких как геттеры, сеттеры и конструкторы, через аннотации.

### Liquibase Core
Позволяет управлять миграциями базы данных с использованием changelog файлов.

### MySQL Connector Java
Драйвер JDBC для взаимодействия с MySQL базой данных.

### Spring Boot Starter Data JPA
Интеграция Spring Data JPA для удобного доступа и управления данными в базе данных с использованием Java Persistence API (JPA).

### Jsoup
Библиотека для парсинга HTML и XML документов, удобно используемая для извлечения данных из веб-страниц.

### Apache Lucene Morphology
Библиотеки для морфологического анализа текста с использованием Apache Lucene, поддерживающие русский и английский языки.

- **morph** (org.apache.lucene.morphology:morph:1.5)
- **morphology** (org.apache.lucene.analysis:morphology:1.5)
- **dictionary-reader** (org.apache.lucene.morphology:dictionary-reader:1.5)
- **english** (org.apache.lucene.morphology:english:1.5)
- **russian** (org.apache.lucene.morphology:russian:1.5)

### Caffeine
Высокопроизводительный кэш для Java, используемый для ускорения доступа к данным в приложении.

### Spring Boot Starter Test
Зависимость для модульного тестирования Spring Boot приложений.

### Mockito Core
Фреймворк для создания моков и проверки поведения в модульных тестах.

### Liquibase Maven Plugin
Плагин для интеграции Liquibase с Maven, автоматизирующий процесс миграций базы данных через changelog файлы.



