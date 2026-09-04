      *> ПОРОЖДЁННЫЙ ФАЙЛ. Правится contracts/reports/digest-v1.yaml.
      *>
      *> Одна строка выгрузки: пост с автором и днём. Свод считает по этим строкам, сам их не изменяя.
      *>
      *> Длина записи: 380 байт. Смещения складывает компилятор —
      *> записанные рядом числа однажды разойдутся с настоящими.
       01  DIGEST-POST.
           05  POST-ID                    PIC X(40).
           05  AUTHOR-NICK                PIC X(80).
           05  AUTHOR-DISPLAY-NAME        PIC X(240).
           05  CREATED-DATE               PIC 9(8).
           05  BODY-BYTES                 PIC 9(6).
           05  BODY-CHARS                 PIC 9(6).
