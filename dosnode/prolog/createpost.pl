% =====================================================================
%  Второе мнение о правилах создания поста.
%
%  ЗАЧЕМ. Правильность доменных правил подтверждают тесты, написанные
%  тем же человеком, что и правила: 83 случая и 50 000 случайных входов
%  в tstdomn.pas. Это много, но у всей этой проверки один изъян —
%  ошибка в ПОНИМАНИИ правила уходит в обе стороны разом, и обе стороны
%  согласны.
%
%  Здесь те же правила записаны заново, на другом языке, из ADR и
%  контракта, — и прогоняются по корпусу, порождённому НАСТОЯЩИМ ядром
%  (contracts/generated/domain/createpost.tsv). Расхождение хотя бы на
%  одном случае означает, что одна из двух реализаций неверна; какая
%  именно — вопрос разбора, но сам факт расхождения не спрячешь.
%
%  Тот же приём, что с длиной записей свода на Brainfuck (ADR-0019):
%  инструмент с заведомо ДРУГИМ набором возможных ошибок.
%
%  ПОЧЕМУ ПРОЛОГ, А НЕ ЕЩЁ ОДИН ПАСКАЛЬ. Правила домена — это цепочка
%  условий с приоритетом: заблокированному не сообщают, что его текст
%  вдобавок пуст. В императивном языке приоритет выражается ПОРЯДКОМ
%  строк и потому легко воспроизводится не глядя. Здесь он записан
%  явными условиями, и списать порядок не выйдет: его приходится
%  сформулировать заново.
%
%  ПРЕДЕЛЫ ПРИХОДЯТ ИЗ КОНТРАКТА аргументами, а не из корпуса. Возьми мы
%  их из корпуса — расхождение в самих пределах прошло бы незамеченным:
%  обе стороны считали бы по одному и тому же неверному числу.
% =====================================================================

:- initialization(main, main).

% --- Правила ---------------------------------------------------------

% Идентификатор: непусто, не длиннее сорока байт, только буквы, цифры,
% подчёркивание и дефис.
id_char(C) :- C >= 0'a, C =< 0'z.
id_char(C) :- C >= 0'A, C =< 0'Z.
id_char(C) :- C >= 0'0, C =< 0'9.
id_char(0'_).
id_char(0'-).

id_well_formed(Bytes) :-
    Bytes \= [],
    length(Bytes, N),
    N =< 40,
    forall(member(C, Bytes), id_char(C)).

% Отрицательный счётчик означает «оболочка не считала», а не «ноль».
actor_complete(UserBytes, Posts, Comments) :-
    id_well_formed(UserBytes),
    Posts >= 0,
    Comments >= 0.

banned_or_gone('BANNED').
banned_or_gone('DELETED').

% --- Разбор UTF-8 ----------------------------------------------------
%
% Строгий: отвергает продолжающий байт на месте ведущего, избыточные
% записи, суррогаты и всё за U+10FFFF. Записан по таблице Юникода, а не
% списан с паскалевского StrCharLen, — иначе это была бы не вторая
% реализация, а копия.

continuation(B) :- B >= 0x80, B =< 0xBF.

% utf8_count(+Байты, -ЧислоЗнаков) — не выполняется на неверной записи.
utf8_count([], 0).
utf8_count([B|T], N) :-
    B =< 0x7F,
    utf8_count(T, N0),
    N is N0 + 1.
utf8_count([B,B2|T], N) :-
    B >= 0xC2, B =< 0xDF,
    continuation(B2),
    utf8_count(T, N0),
    N is N0 + 1.
utf8_count([B,B2,B3|T], N) :-
    B >= 0xE0, B =< 0xEF,
    continuation(B2),
    continuation(B3),
    % Избыточная тройка и суррогаты видны по второму байту.
    (  B =:= 0xE0 -> B2 >= 0xA0
    ;  B =:= 0xED -> B2 =< 0x9F
    ;  true
    ),
    utf8_count(T, N0),
    N is N0 + 1.
utf8_count([B,B2,B3,B4|T], N) :-
    B >= 0xF0, B =< 0xF4,
    continuation(B2),
    continuation(B3),
    continuation(B4),
    (  B =:= 0xF0 -> B2 >= 0x90
    ;  B =:= 0xF4 -> B2 =< 0x8F
    ;  true
    ),
    utf8_count(T, N0),
    N is N0 + 1.

blank_byte(0'\s).
blank_byte(9).
blank_byte(10).
blank_byte(13).

% Текст из одних пробельных пуст по смыслу: пост из десяти пробелов —
% это пустой пост.
all_blank(Bytes) :- forall(member(B, Bytes), blank_byte(B)).

% text_verdict(+Байты, +ПределЗнаков, -Приговор)
text_verdict(Bytes, _, bad_encoding) :-
    \+ utf8_count(Bytes, _), !.
text_verdict(Bytes, _, empty) :-
    all_blank(Bytes), !.
text_verdict(Bytes, Max, too_long) :-
    utf8_count(Bytes, N),
    N > Max, !.
text_verdict(_, _, ok).

% --- Решение ---------------------------------------------------------
%
% Порядок условий и есть предмет второго мнения: какой отказ побеждает,
% когда подходят несколько.

decide(User, _, Posts, Comments, _, _, _, _, rejected('INSUFFICIENT_CONTEXT')) :-
    \+ actor_complete(User, Posts, Comments), !.
decide(_, _, _, _, PostId, _, _, _, rejected('INSUFFICIENT_CONTEXT')) :-
    \+ id_well_formed(PostId), !.
decide(_, Status, _, _, _, _, _, _, rejected('ACTOR_BANNED')) :-
    banned_or_gone(Status), !.
decide(_, _, _, _, _, Body, MaxLen, _, rejected(Code)) :-
    text_verdict(Body, MaxLen, V),
    V \= ok, !,
    text_code(V, Code).
decide(_, _, Posts, _, _, _, _, Rate, rejected('POST_RATE_EXCEEDED')) :-
    Posts >= Rate, !.
decide(_, _, _, _, _, _, _, _, accepted).

text_code(bad_encoding, 'TEXT_ENCODING_INVALID').
text_code(empty, 'POST_BODY_EMPTY').
text_code(too_long, 'POST_BODY_TOO_LONG').

% --- Чтение корпуса --------------------------------------------------

hex_byte(H, L, B) :-
    hex_digit(H, HV),
    hex_digit(L, LV),
    B is HV * 16 + LV.

hex_digit(C, V) :- C >= 0'0, C =< 0'9, !, V is C - 0'0.
hex_digit(C, V) :- C >= 0'a, C =< 0'f, !, V is C - 0'a + 10.

% Дефис означает пустую строку: пустое поле в TSV неотличимо от
% пропущенного, а разница тут есть.
hex_bytes("-", []) :- !.
hex_bytes(Text, Bytes) :-
    string_codes(Text, Codes),
    hex_pairs(Codes, Bytes).

hex_pairs([], []).
hex_pairs([H,L|T], [B|Rest]) :-
    hex_byte(H, L, B),
    hex_pairs(T, Rest).

% --- Прогон ----------------------------------------------------------

check_line(Line, MaxLen, Rate, Result) :-
    split_string(Line, "\t", "", Fields),
    Fields = [IdText, UserHex, StatusText, PostsText, CommentsText,
              PostIdHex, BodyHex, VerdictText, CodeText],
    hex_bytes(UserHex, User),
    hex_bytes(PostIdHex, PostId),
    hex_bytes(BodyHex, Body),
    atom_string(Status, StatusText),
    number_string(Posts, PostsText),
    number_string(Comments, CommentsText),
    decide(User, Status, Posts, Comments, PostId, Body, MaxLen, Rate, Mine),
    expected(VerdictText, CodeText, Theirs),
    (   Mine == Theirs
    ->  Result = agree
    ;   Result = disagree(IdText, Theirs, Mine)
    ).

expected("accepted", _, accepted).
expected("rejected", CodeText, rejected(Code)) :- atom_string(Code, CodeText).

run([], _, _, 0, 0).
run([Line|Rest], MaxLen, Rate, Agreed, Bad) :-
    (   string_concat("#", _, Line)
    ->  run(Rest, MaxLen, Rate, Agreed, Bad)
    ;   Line == ""
    ->  run(Rest, MaxLen, Rate, Agreed, Bad)
    ;   check_line(Line, MaxLen, Rate, R),
        run(Rest, MaxLen, Rate, A0, B0),
        (   R = disagree(Id, Theirs, Mine)
        ->  format("  случай ~w: ядро ~q, пролог ~q~n", [Id, Theirs, Mine]),
            Agreed = A0, Bad is B0 + 1
        ;   Agreed is A0 + 1, Bad = B0
        )
    ).

% Предел из корпуса сверяется с пределом из контракта отдельно: ядро,
% собранное с другими числами, — это расхождение само по себе, и увидеть
% его надо здесь, а не в виде загадочных отказов.
limits_line(Lines, MaxLen, Rate) :-
    member(Line, Lines),
    split_string(Line, "\t", "", ["# limits", MaxText, RateText]),
    number_string(MaxLen, MaxText),
    number_string(Rate, RateText), !.

main([Path, MaxLenArg, RateArg]) :-
    % atom_number, а не number_string: аргументы командной строки
    % приезжают атомами, и number_string на них жалуется на тип.
    atom_number(MaxLenArg, MaxLen),
    atom_number(RateArg, Rate),
    read_file_to_string(Path, Text, []),
    split_string(Text, "\n", "\r", Lines),
    (   limits_line(Lines, CorpusMax, CorpusRate)
    ->  true
    ;   format("в корпусе нет строки с пределами~n"), halt(1)
    ),
    (   CorpusMax =:= MaxLen, CorpusRate =:= Rate
    ->  true
    ;   format("ПРЕДЕЛЫ РАЗОШЛИСЬ: контракт ~w/~w, корпус ~w/~w~n",
               [MaxLen, Rate, CorpusMax, CorpusRate]),
        halt(1)
    ),
    run(Lines, MaxLen, Rate, Agreed, Bad),
    (   Bad =:= 0
    ->  format("согласие на всех ~w случаях~n", [Agreed]), halt(0)
    ;   format("РАСХОЖДЕНИЙ: ~w из ~w~n", [Bad, Agreed + Bad]), halt(1)
    ).
main(_) :-
    format("нужно: createpost.pl <корпус> <предел длины> <предел частоты>~n"),
    halt(2).
