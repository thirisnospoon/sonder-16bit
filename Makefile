# Тонкая обёртка над ./sonder — там вся логика.
#
# Отдельный shell-скрипт, а не Makefile с целями, потому что make сам по себе
# зависимость, которой на чистой машине может не оказаться. Например, в WSL
# Ubuntu по умолчанию его нет. Docker есть, bash есть — этого достаточно.
#
# Makefile существует для CI и для мышечной памяти: make verify работает.

.DEFAULT_GOAL := help

.PHONY: help bootstrap verify contracts contracts-selftest spikes clean check-eol
help bootstrap verify contracts contracts-selftest spikes clean check-eol:
	@./sonder $@
