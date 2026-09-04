% =====================================================================
%  Второе мнение: создание поста.
%
%  Правила записаны заново по контракту и ADR-0011, а не срисованы с
%  DecideCreatePost. Смысл именно в этом: копия ошибается там же, где
%  оригинал (ADR-0022).
%
%  ПОРЯДОК УСЛОВИЙ И ЕСТЬ ПРЕДМЕТ ВТОРОГО МНЕНИЯ. Какой отказ побеждает,
%  когда подходят несколько, — самое лёгкое место для расхождения: в
%  императивном языке приоритет выражен порядком строк и списывается не
%  глядя, здесь его приходится сформулировать.
%
%  Запускается через ops/ci/domain-crosscheck.sh.
% =====================================================================

:- use_module(rules).
:- initialization(main, main).

banned_or_gone('BANNED').
banned_or_gone('DELETED').

text_code(bad_encoding, 'TEXT_ENCODING_INVALID').
text_code(empty, 'POST_BODY_EMPTY').
text_code(too_long, 'POST_BODY_TOO_LONG').

% Поля корпуса: номер, пользователь, статус, постов за час,
% комментариев за час, идентификатор поста, тело.
solve([_, UserHex, StatusText, PostsText, CommentsText, PostIdHex, BodyHex],
      limits(MaxLen, Rate), Verdict) :-
    hex_bytes(UserHex, User),
    hex_bytes(PostIdHex, PostId),
    hex_bytes(BodyHex, Body),
    atom_string(Status, StatusText),
    number_string(Posts, PostsText),
    number_string(Comments, CommentsText),
    decide(User, Status, Posts, Comments, PostId, Body, MaxLen, Rate, Verdict).

% Отрицательный счётчик означает «оболочка не считала», а не «ноль».
actor_complete(User, Posts, Comments) :-
    id_well_formed(User),
    Posts >= 0,
    Comments >= 0.

decide(User, _, Posts, Comments, _, _, _, _, rejected('INSUFFICIENT_CONTEXT')) :-
    \+ actor_complete(User, Posts, Comments), !.
decide(_, _, _, _, PostId, _, _, _, rejected('INSUFFICIENT_CONTEXT')) :-
    \+ id_well_formed(PostId), !.
% Права раньше формы: заблокированному незачем сообщать, что его текст
% к тому же слишком длинный.
decide(_, Status, _, _, _, _, _, _, rejected('ACTOR_BANNED')) :-
    banned_or_gone(Status), !.
decide(_, _, _, _, _, Body, MaxLen, _, rejected(Code)) :-
    text_verdict(Body, MaxLen, V),
    V \= ok, !,
    text_code(V, Code).
decide(_, _, Posts, _, _, _, _, Rate, rejected('POST_RATE_EXCEEDED')) :-
    Posts >= Rate, !.
decide(_, _, _, _, _, _, _, _, accepted).

main([Path, MaxLenArg, RateArg]) :-
    % atom_number, а не number_string: аргументы командной строки
    % приезжают атомами, и number_string на них жалуется на тип.
    atom_number(MaxLenArg, MaxLen),
    atom_number(RateArg, Rate),
    run_corpus(Path, limits(MaxLen, Rate), solve, Bad),
    (   Bad =:= 0 -> halt(0) ;   halt(1)  ).
main(_) :-
    format("нужно: createpost.pl <корпус> <предел длины> <предел частоты>~n"),
    halt(2).
