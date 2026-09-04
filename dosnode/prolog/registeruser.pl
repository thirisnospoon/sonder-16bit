% =====================================================================
%  Второе мнение: регистрация пользователя.
%
%  Правила другого рода, чем у создания поста, и потому эта операция
%  взята второй: там текст и частота, здесь ФОРМА ИМЕНИ и занятость.
%
%  Две тонкости, каждая — готовое место для расхождения:
%
%    1. У ника свой набор знаков, СТРОЖЕ идентификатора: только нижний
%       регистр, цифры и подчёркивание. Дефис в идентификатор входит, в
%       ник — нет. Реализация, взявшая одну проверку на оба, примет
%       «u-andrey» за законный ник.
%
%    2. Пустое и слишком длинное отображаемое имя дают ОДИН код:
%       контракт объединяет их, потому что для человека и то и другое
%       означает «поправьте имя». А вот неверная кодировка — отдельный
%       код, и объединять её с ними нельзя: это не про имя, а про байты.
%
%  Прав здесь не проверяют вовсе: регистрируется тот, у кого их ещё нет.
% =====================================================================

:- use_module(rules).
:- initialization(main, main).

% Поля корпуса: номер, идентификатор, ник, отображаемое имя, занят ли ник.
solve([_, UserIdHex, NickHex, NameHex, TakenText],
      limits(NickMin, NickMax, NameMax), Verdict) :-
    hex_bytes(UserIdHex, UserId),
    hex_bytes(NickHex, Nick),
    hex_bytes(NameHex, Name),
    taken(TakenText, Taken),
    decide(UserId, Nick, Name, Taken, NickMin, NickMax, NameMax, Verdict).

taken("yes", true).
taken("no", false).

decide(UserId, _, _, _, _, _, _, rejected('INSUFFICIENT_CONTEXT')) :-
    \+ id_well_formed(UserId), !.
decide(_, Nick, _, _, Min, Max, _, rejected('NICK_FORMAT_INVALID')) :-
    \+ nick_well_formed(Nick, Min, Max), !.
decide(_, _, Name, _, _, _, NameMax, rejected('TEXT_ENCODING_INVALID')) :-
    text_verdict(Name, NameMax, bad_encoding), !.
decide(_, _, Name, _, _, _, NameMax, rejected('DISPLAY_NAME_INVALID')) :-
    text_verdict(Name, NameMax, V),
    memberchk(V, [empty, too_long]), !.
decide(_, _, _, true, _, _, _, rejected('NICK_TAKEN')) :- !.
decide(_, _, _, _, _, _, _, accepted).

main([Path, NickMinArg, NickMaxArg, NameMaxArg]) :-
    atom_number(NickMinArg, NickMin),
    atom_number(NickMaxArg, NickMax),
    atom_number(NameMaxArg, NameMax),
    run_corpus(Path, limits(NickMin, NickMax, NameMax), solve, Bad),
    (   Bad =:= 0 -> halt(0) ;   halt(1)  ).
main(_) :-
    format("нужно: registeruser.pl <корпус> <мин ника> <макс ника> <макс имени>~n"),
    halt(2).
