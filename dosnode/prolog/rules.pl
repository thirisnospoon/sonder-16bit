% =====================================================================
%  Второе мнение о доменных правилах: общая часть.
%
%  Здесь то, что разделяют все операции: разбор UTF-8, форма
%  идентификатора, приговор тексту, чтение корпуса и сверка.
%
%  ОБЩЕЕ — НЕ ЗНАЧИТ СПИСАННОЕ. Правила записаны по контракту и ADR, а
%  не срисованы с паскалевских исходников: копия ошибается там же, где
%  оригинал, и вторым мнением не является (ADR-0022).
%
%  Ровно один экземпляр на все операции — потому что копия разошлась бы
%  молча: разбор UTF-8, повторённый семь раз, ошибётся в одном из семи.
% =====================================================================

:- module(rules, [
       id_well_formed/1,
       nick_well_formed/3,
       utf8_count/2,
       text_verdict/3,
       hex_bytes/2,
       run_corpus/4
   ]).

% --- Форма идентификатора --------------------------------------------
%
% Непусто, не длиннее сорока байт, только буквы обоих регистров, цифры,
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

% --- Форма ника -------------------------------------------------------
%
% Строже идентификатора: только НИЖНИЙ регистр, цифры и подчёркивание,
% и длина в границах контракта. Дефиса тут нет — в ник он не входит.
%
% Длина меряется в БАЙТАХ, и это не упущение: допустимые знаки все
% однобайтовые, так что байт и знак здесь одно и то же. Разница
% появилась бы ровно там, где ник и так уже отвергнут.

lower_alnum(C) :- C >= 0'a, C =< 0'z.
lower_alnum(C) :- C >= 0'0, C =< 0'9.
lower_alnum(0'_).

nick_well_formed(Bytes, Min, Max) :-
    Bytes \= [],
    length(Bytes, N),
    N >= Min,
    N =< Max,
    forall(member(C, Bytes), lower_alnum(C)).

% --- Разбор UTF-8 -----------------------------------------------------
%
% Строгий: отвергает продолжающий байт на месте ведущего, избыточные
% записи, суррогаты и всё за U+10FFFF.

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

% --- Приговор тексту --------------------------------------------------

blank_byte(0'\s).
blank_byte(9).
blank_byte(10).
blank_byte(13).

% Текст из одних пробельных пуст по смыслу: пост из десяти пробелов —
% это пустой пост.
all_blank(Bytes) :- forall(member(B, Bytes), blank_byte(B)).

% text_verdict(+Байты, +ПределЗнаков, -Приговор)
%
% Порядок важен: сначала кодировка, потом пустота, потом длина. Иначе
% неверный UTF-8 из одних байтов-пробелов объявили бы просто пустым.
text_verdict(Bytes, _, bad_encoding) :-
    \+ utf8_count(Bytes, _), !.
text_verdict(Bytes, _, empty) :-
    all_blank(Bytes), !.
text_verdict(Bytes, Max, too_long) :-
    utf8_count(Bytes, N),
    N > Max, !.
text_verdict(_, _, ok).

% --- Чтение корпуса ---------------------------------------------------

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
    hex_digit(H, HV),
    hex_digit(L, LV),
    B is HV * 16 + LV,
    hex_pairs(T, Rest).

% --- Сверка -----------------------------------------------------------
%
% run_corpus(+Путь, +Пределы, :Решатель, -Плохих)
%
% Решатель зовётся как call(Решатель, ПоляСтроки, Пределы, МоёРешение).
% Строка корпуса кончается двумя полями — что решило ядро; их сюда не
% передают, чтобы решатель не мог подсмотреть ответ.

:- meta_predicate run_corpus(+, +, 3, -).

run_corpus(Path, Limits, Solver, Bad) :-
    read_file_to_string(Path, Text, []),
    split_string(Text, "\n", "\r", Lines),
    foldl(check_line(Limits, Solver), Lines, 0-0, Agreed-Bad),
    (   Bad =:= 0
    ->  format("согласие на всех ~w случаях~n", [Agreed])
    ;   Total is Agreed + Bad,
        format("РАСХОЖДЕНИЙ: ~w из ~w~n", [Bad, Total])
    ).

check_line(_, _, Line, Acc, Acc) :-
    (   Line == ""
    ;   string_concat("#", _, Line)
    ), !.
check_line(Limits, Solver, Line, A0-B0, A-B) :-
    split_string(Line, "\t", "", Fields),
    length(Fields, N),
    Take is N - 2,
    length(Input, Take),
    append(Input, [VerdictText, CodeText], Fields),
    call(Solver, Input, Limits, Mine),
    expected(VerdictText, CodeText, Theirs),
    (   Mine == Theirs
    ->  A is A0 + 1, B = B0
    ;   nth0(0, Input, Id),
        format("  случай ~w: ядро ~q, второе мнение ~q~n", [Id, Theirs, Mine]),
        A = A0, B is B0 + 1
    ).

expected("accepted", _, accepted).
expected("rejected", CodeText, rejected(Code)) :- atom_string(Code, CodeText).
