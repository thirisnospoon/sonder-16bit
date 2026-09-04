      *>*****************************************************************
      *> НОЧНОЙ СВОД SONDER
      *>
      *> Читает плоский файл фиксированной ширины, выгруженный оболочкой,
      *> и печатает отчёт с итогами по автору и общим итогом.
      *>
      *> ПОЧЕМУ ЭТО COBOL, а не метод в оболочке: решение и цена его
      *> записаны в ADR-0018. Коротко — форма задачи ровно та, под
      *> которую язык сделан: последовательный проход, контрольный
      *> переход по автору, накопление итогов.
      *>
      *> ВХОД ОБЯЗАН БЫТЬ ОТСОРТИРОВАН ПО АВТОРУ. Контрольный переход
      *> работает только на упорядоченном входе — это не ограничение
      *> реализации, а свойство приёма: несортированный файл дал бы
      *> столько «итогов по автору», сколько раз автор встретился, и
      *> выглядело бы это правдоподобно. Сортирует выгрузка, потому что
      *> у неё есть индекс, а у нас — нет.
      *>
      *> ПОРЯДОК АВТОРОВ БАЙТОВЫЙ, а не алфавитный по-русски: сравнение
      *> строк в COBOL побайтовое, и для UTF-8 это порядок кодовых
      *> точек. Записано в ADR-0018 как принятая цена.
      *>*****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. DIGEST.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT POSTS-FILE ASSIGN TO POSTS-PATH
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS POSTS-STATUS.
           SELECT REPORT-FILE ASSIGN TO REPORT-PATH
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS REPORT-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  POSTS-FILE.
       COPY "DIGEST.cpy".

       FD  REPORT-FILE.
       01  REPORT-LINE                    PIC X(132).

       WORKING-STORAGE SECTION.

      *> Пути приходят извне: зашитые в программу, они сделали бы её
      *> непригодной ни для проверки, ни для второго запуска.
       01  POSTS-PATH                     PIC X(255).
       01  REPORT-PATH                    PIC X(255).

       01  POSTS-STATUS                   PIC XX.
       01  REPORT-STATUS                  PIC XX.

       01  FLAGS.
           05  END-OF-POSTS               PIC X VALUE "N".
               88  NO-MORE-POSTS          VALUE "Y".
           05  FIRST-RECORD               PIC X VALUE "Y".
               88  IS-FIRST               VALUE "Y".

      *> Ник предыдущей записи: по его смене и происходит контрольный
      *> переход.
       01  PREV-NICK                      PIC X(80).
       01  PREV-DISPLAY                   PIC X(240).

       01  AUTHOR-TOTALS.
           05  A-POSTS                    PIC 9(7) VALUE 0.
           05  A-BYTES                    PIC 9(11) VALUE 0.
           05  A-CHARS                    PIC 9(11) VALUE 0.

       01  GRAND-TOTALS.
           05  G-AUTHORS                  PIC 9(7) VALUE 0.
           05  G-POSTS                    PIC 9(7) VALUE 0.
           05  G-BYTES                    PIC 9(11) VALUE 0.
           05  G-CHARS                    PIC 9(11) VALUE 0.

      *> Разница байтов и символов — мера того, сколько в системе
      *> не-латиницы. Считается здесь, а не в оболочке, потому что это
      *> вопрос свода, а не вопрос записи.
       01  OVERHEAD-PCT                   PIC 9(3)V99 VALUE 0.

      *> РАЗМЕТКА ОТЧЁТА, И ТУТ ЕСТЬ ЛОВУШКА, ради которой стоит
      *> читать медленно.
      *>
      *> PIC считает БАЙТЫ, а колонки на бумаге — ЗНАКИ. Для латиницы это
      *> одно и то же, для кириллицы — вдвое разное. Заголовок
      *> `PIC X(24) VALUE "АВТОР"` занимает пять знаков и десять байт, и
      *> если объявить ширину по знакам, компилятор молча обрежет хвост:
      *> первая сборка дала пять предупреждений «value size exceeds data
      *> size» и обрезанные русские заголовки.
      *>
      *> Поэтому ширины ниже посчитаны в байтах, а сами литералы уже
      *> добиты пробелами до нужного числа ЗНАКОВ. Числа рядом с каждым
      *> полем — не украшение: пересчитывать их в уме нельзя.
      *>
      *> ОТОБРАЖАЕМОЕ ИМЯ В ОТЧЁТ НЕ ИДЁТ, и это следствие той же
      *> ловушки. Имя кириллическое, его знаковая ширина не равна
      *> байтовой, и колонка после него поехала бы на столько знаков,
      *> сколько в имени русских букв — у каждой строки по-своему. Ник по
      *> контракту латиница, цифры и подчёркивание, и как раз он автора
      *> определяет однозначно. Имя остаётся в записи выгрузки: оно
      *> данные, просто не для этого отчёта.
       01  DETAIL-LINE.
           05  FILLER                     PIC X(2)  VALUE SPACES.
           05  D-NICK                     PIC X(24).
           05  FILLER                     PIC X(2)  VALUE SPACES.
           05  D-POSTS                    PIC ZZZ,ZZ9.
           05  FILLER                     PIC X(2)  VALUE SPACES.
           05  D-BYTES                    PIC ZZZ,ZZZ,ZZ9.
           05  FILLER                     PIC X(2)  VALUE SPACES.
           05  D-CHARS                    PIC ZZZ,ZZZ,ZZ9.

      *> 26 знаков, 36 байт.
       01  HEAD-1                         PIC X(36)
           VALUE "  SONDER: НОЧНОЙ СВОД     ".

      *> 61 знак, 84 байта. Числовые подписи прижаты вправо — под числа.
       01  HEAD-2                         PIC X(84)
           VALUE "  АВТОР                      ПОСТОВ         БАЙТ     СИМВОЛОВ".

       01  RULE-LINE                      PIC X(61) VALUE ALL "-".

      *> Префикс «ИТОГО»: 28 знаков, 33 байта. Дальше те же числовые
      *> колонки, что и в строке автора.
       01  TOTAL-LINE.
           05  FILLER                     PIC X(33)
               VALUE "  ИТОГО                     ".
           05  T-POSTS                    PIC ZZZ,ZZ9.
           05  FILLER                     PIC X(2)  VALUE SPACES.
           05  T-BYTES                    PIC ZZZ,ZZZ,ZZ9.
           05  FILLER                     PIC X(2)  VALUE SPACES.
           05  T-CHARS                    PIC ZZZ,ZZZ,ZZ9.

      *> 26 знаков, 33 байта.
       01  AUTHORS-LINE.
           05  FILLER                     PIC X(33)
               VALUE "  АВТОРОВ                 ".
           05  A-COUNT                    PIC ZZZ,ZZ9.

      *> 26 знаков, 38 байт.
       01  OVERHEAD-LINE.
           05  FILLER                     PIC X(38)
               VALUE "  БАЙТ НА СИМВОЛ          ".
           05  O-PCT                      PIC Z9.99.

       PROCEDURE DIVISION.

       MAIN-PARA.
           ACCEPT POSTS-PATH FROM ENVIRONMENT "SONDER_DIGEST_INPUT"
           ACCEPT REPORT-PATH FROM ENVIRONMENT "SONDER_DIGEST_OUTPUT"

           IF POSTS-PATH = SPACES OR REPORT-PATH = SPACES
               DISPLAY "нужны SONDER_DIGEST_INPUT и SONDER_DIGEST_OUTPUT"
                   UPON SYSERR
               MOVE 2 TO RETURN-CODE
               GOBACK
           END-IF

           OPEN INPUT POSTS-FILE
           IF POSTS-STATUS NOT = "00"
               DISPLAY "не открыть вход: " POSTS-STATUS UPON SYSERR
               MOVE 3 TO RETURN-CODE
               GOBACK
           END-IF

           OPEN OUTPUT REPORT-FILE
           IF REPORT-STATUS NOT = "00"
               DISPLAY "не открыть выход: " REPORT-STATUS UPON SYSERR
               MOVE 3 TO RETURN-CODE
               GOBACK
           END-IF

           PERFORM WRITE-HEADER
           PERFORM READ-POST
           PERFORM UNTIL NO-MORE-POSTS
               PERFORM HANDLE-RECORD
               PERFORM READ-POST
           END-PERFORM

      *>     Последний автор итога ещё не получил: контрольный переход
      *>     срабатывает на СМЕНЕ, а после последней записи менять уже
      *>     нечему. Забыть это — классическая потеря последней группы.
           IF NOT IS-FIRST
               PERFORM WRITE-AUTHOR-TOTAL
           END-IF

           PERFORM WRITE-FOOTER

           CLOSE POSTS-FILE
           CLOSE REPORT-FILE
           GOBACK.

       READ-POST.
           READ POSTS-FILE
               AT END SET NO-MORE-POSTS TO TRUE
           END-READ.

       HANDLE-RECORD.
           IF IS-FIRST
               MOVE AUTHOR-NICK TO PREV-NICK
               MOVE AUTHOR-DISPLAY-NAME TO PREV-DISPLAY
               MOVE "N" TO FIRST-RECORD
               ADD 1 TO G-AUTHORS
           ELSE
               IF AUTHOR-NICK NOT = PREV-NICK
                   PERFORM WRITE-AUTHOR-TOTAL
                   MOVE AUTHOR-NICK TO PREV-NICK
                   MOVE AUTHOR-DISPLAY-NAME TO PREV-DISPLAY
                   MOVE 0 TO A-POSTS
                   MOVE 0 TO A-BYTES
                   MOVE 0 TO A-CHARS
                   ADD 1 TO G-AUTHORS
               END-IF
           END-IF

           ADD 1 TO A-POSTS
           ADD BODY-BYTES TO A-BYTES
           ADD BODY-CHARS TO A-CHARS

           ADD 1 TO G-POSTS
           ADD BODY-BYTES TO G-BYTES
           ADD BODY-CHARS TO G-CHARS.

       WRITE-AUTHOR-TOTAL.
           MOVE PREV-NICK TO D-NICK
           MOVE A-POSTS TO D-POSTS
           MOVE A-BYTES TO D-BYTES
           MOVE A-CHARS TO D-CHARS
           WRITE REPORT-LINE FROM DETAIL-LINE.

       WRITE-HEADER.
           WRITE REPORT-LINE FROM HEAD-1
           WRITE REPORT-LINE FROM RULE-LINE
           WRITE REPORT-LINE FROM HEAD-2
           WRITE REPORT-LINE FROM RULE-LINE.

       WRITE-FOOTER.
           WRITE REPORT-LINE FROM RULE-LINE
           MOVE G-POSTS TO T-POSTS
           MOVE G-BYTES TO T-BYTES
           MOVE G-CHARS TO T-CHARS
           WRITE REPORT-LINE FROM TOTAL-LINE

           MOVE G-AUTHORS TO A-COUNT
           WRITE REPORT-LINE FROM AUTHORS-LINE

      *>     Байт на символ: единица означает чистую латиницу, два —
      *>     сплошную кириллицу. Деления на ноль здесь быть не может
      *>     только если записей не было вовсе — этот случай отдельно.
           IF G-CHARS > 0
               COMPUTE OVERHEAD-PCT = G-BYTES / G-CHARS
               MOVE OVERHEAD-PCT TO O-PCT
               WRITE REPORT-LINE FROM OVERHEAD-LINE
           END-IF.
