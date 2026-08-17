# Name: HookUsername
# Description: хук юзернейма
# authors: @neistv
# version: 1.3.0
# meta developer: @latexmods
# meta banner: https://github.com/neistv/mods/raw/main/assets/banners/HookUsername.png

import asyncio
import html
import logging
import random
import re
import time
import unicodedata
from enum import Enum

import requests
from bs4 import BeautifulSoup
from telethon.tl import functions
from herokutl.types import Message

from .. import loader, utils

logger = logging.getLogger(__name__)


class UsernameStatus(Enum):
    AVAILABLE = "available"
    UNAVAILABLE = "unavailable"
    INVALID = "invalid"
    PURCHASABLE = "purchasable"
    FLOOD_WAIT = "flood_wait"
    ERROR = "error"


class FragmentStatus(Enum):
    SOLD = "sold"
    AVAILABLE = "available"
    NOT_FOUND = "not_found"
    ERROR = "error"


class GrabStatus(Enum):
    SUCCESS = "success"
    USERNAME_TAKEN = "username_taken"
    USERNAME_INVALID = "username_invalid"
    USERNAME_PURCHASABLE = "username_purchasable"
    FLOOD_WAIT = "flood_wait"
    PUBLIC_LIMIT = "public_limit"
    CHANNEL_LIMIT = "channel_limit"
    USER_RESTRICTED = "user_restricted"
    BAD_TITLE = "bad_title"
    BAD_ABOUT = "bad_about"
    NO_RIGHTS = "no_rights"
    ERROR = "error"


@loader.tds
class HookUsernameMod(loader.Module):
    """хук юзернейма"""

    strings = {
        "name": "HookUsername",
        "no_args": (
            "<b><tg-emoji emoji-id='5220197908342648622'>❗️</tg-emoji> "
            "укажи юзак!!</b>"
        ),
        "bad_length": (
            "<b><tg-emoji emoji-id='5220197908342648622'>❗️</tg-emoji> "
            "юзернейм должен содержать от 5 до 32 символов!!</b>"
        ),
        "bad_chars": (
            "<b><tg-emoji emoji-id='5220197908342648622'>❗️</tg-emoji> "
            "в юзернейме допустимы только латинские буквы, цифры и _ !!</b>"
        ),
        "available": (
            "юзак <b>@{username}</b> — свободен!!!\n\n"
            "хочешь занять этот юзернейм?"
        ),
        "available_no_inline": (
            "<tg-emoji emoji-id='5219901967916084166'>💥</tg-emoji> "
            "<b>@{username}</b> — свободен, но inline-форму создать не удалось. "
            "Повтори команду позже."
        ),
        "grab_button": "✔ занять",
        "close_button": "✖ закрыть",
        "stop_button": "⛔ стоп",
        "back_button": "◀ назад",
        "checking": "<b>проверяю.. @{username}...</b>",
        "fragment_sold": (
            "<tg-emoji emoji-id='5220197908342648622'>❗️</tg-emoji> "
            "<b>@{username}</b> — продан на Fragment.\n\n"
            "<tg-emoji emoji-id='5219943216781995020'>⚡️</tg-emoji> "
            "<b>найден на Fragment:</b>\n"
            "{price_line}"
            "<tg-emoji emoji-id='5902449142575141204'>🔗</tg-emoji> "
            "<b>ссылка:</b> <a href=\"{url}\">{url}</a>"
        ),
        "fragment_available": (
            "<tg-emoji emoji-id='5220197908342648622'>❗️</tg-emoji> "
            "<b>@{username}</b> — занят.\n\n"
            "<tg-emoji emoji-id='5219943216781995020'>⚡️</tg-emoji> "
            "<b>найден на Fragment:</b>\n"
            "{price_line}"
            "<tg-emoji emoji-id='5902449142575141204'>🔗</tg-emoji> "
            "<b>ссылка:</b> <a href=\"{url}\">{url}</a>"
        ),
        "price_line": (
            "<tg-emoji emoji-id='6039802097916974085'>🪙</tg-emoji> "
            "<b>цена:</b> <code>{price}</code> TON\n"
        ),
        "occupied": (
            "<tg-emoji emoji-id='5220197908342648622'>❗️</tg-emoji> "
            "<b>@{username}</b> — занят или недоступен для назначения."
        ),
        "purchasable": (
            "<tg-emoji emoji-id='6039802097916974085'>🪙</tg-emoji> "
            "<b>@{username}</b> доступен только как коллекционный юзернейм."
        ),
        "fragment_error": (
            "\n\n<tg-emoji emoji-id='5220197908342648622'>❗️</tg-emoji> "
            "<i>Fragment временно не удалось проверить.</i>"
        ),
        "check_error": (
            "<tg-emoji emoji-id='5220197908342648622'>❗️</tg-emoji> "
            "<b>Не удалось проверить @{username} из-за ошибки Telegram. "
            "Попробуй позже.</b>"
        ),
        "flood_wait": (
            "<b>⏳ Telegram ограничил проверки. Повтори через {seconds} сек.</b>"
        ),
        "prefix_bad": (
            "<b>❗️ префикс должен содержать только латинские буквы, цифры и _ "
            "и быть не длиннее 31 символа.</b>"
        ),
        "count_bad": (
            "<b>❗️ количество проверок должно быть числом от 1 до {maximum}.</b>"
        ),
        "find_running": "<b>⏳ уже идёт поиск, подожди...</b>",
        "find_start": (
            "<b>🔍 ищу свободные юзернеймы {mode}...\n"
            "проверено: 0 / {total}</b>"
        ),
        "find_progress": (
            "<b>🔍 ищу {mode}...\n"
            "проверено: {checked} / {total}\n\n"
            "найдено: {found_count}\n{preview}</b>"
        ),
        "find_nothing": (
            "<b>😔 свободных юзернеймов {mode} не найдено.\n"
            "Попробуй другой префикс или запусти снова.</b>"
        ),
        "find_stopped": (
            "<b>⛔ поиск остановлен.\nПроверено: {checked} / {total}.\n"
            "Найдено: {found_count}.</b>"
        ),
        "find_flood": (
            "<b>⏳ поиск остановлен из-за ограничения Telegram.\n"
            "Проверено: {checked} / {total}.\n"
            "Повтори примерно через {seconds} сек.</b>"
        ),
        "find_error": (
            "<b>❗️ поиск остановлен из-за ошибки Telegram.\n"
            "Проверено: {checked} / {total}. Попробуй позже.</b>"
        ),
        "find_preview_empty": "пока ничего...",
        "find_result": (
            "<tg-emoji emoji-id='5219901967916084166'>💥</tg-emoji> "
            "<b>найдены свободные юзернеймы {mode}:</b>\n\n"
            "{lines}\n\n"
            "<i>Страница {page}/{pages} · найдено: {total_found}</i>\n\n"
            "нажми чтобы занять:"
        ),
        "find_result_fallback": (
            "<tg-emoji emoji-id='5219901967916084166'>💥</tg-emoji> "
            "<b>найдены свободные юзернеймы {mode}:</b>\n\n{lines}{more_line}"
        ),
        "find_more": (
            "\n\n<i>Показаны первые {shown} из {total_found} найденных; "
            "inline-пагинация недоступна.</i>"
        ),
        "find_page_empty": "Список найденных юзернеймов уже недоступен.",
        "stop_ok": "<b>⛔ поиск остановлен.</b>",
        "stop_idle": "<b>ℹ️ поиск не запущен.</b>",
        "grab_busy": "Уже выполняется другой захват, попробуй ещё раз.",
        "grabbing": "захватываю...",
        "grab_success": (
            "<tg-emoji emoji-id='5219901967916084166'>💥</tg-emoji> "
            "<b>@{username}</b> успешно занят!\n\nКанал: {channel}"
        ),
        "grab_taken": "Юзернейм уже занят. Возможно, его успели забрать после проверки.",
        "grab_invalid": "Telegram отклонил этот юзернейм как недопустимый.",
        "grab_purchasable": "Этот юзернейм доступен только как коллекционный.",
        "grab_flood": "Telegram ограничил операцию. Повтори через {seconds} сек.",
        "grab_public_limit": "Достигнут лимит публичных каналов/юзернеймов аккаунта.",
        "grab_channel_limit": "Достигнут лимит создаваемых каналов аккаунта.",
        "grab_restricted": "Telegram ограничил создание каналов для этого аккаунта.",
        "grab_bad_title": "Telegram отклонил название канала как недопустимое.",
        "grab_bad_about": "Telegram отклонил описание канала как недопустимое.",
        "grab_no_rights": "Telegram не разрешил изменить созданный канал.",
        "grab_error": (
            "Не удалось занять юзернейм из-за ошибки Telegram. "
            "Подробности записаны в лог."
        ),
        "rollback_warning": (
            "\n\n<b>⚠️ Не удалось автоматически удалить временный канал после ошибки. "
            "Проверь список своих каналов вручную.</b>"
        ),
        "grab_error_title": (
            "<tg-emoji emoji-id='5220197908342648622'>❗️</tg-emoji> "
            "<b>Ошибка:</b>\n<code>{error}</code>{rollback_warning}"
        ),
        "mode_prefix": "по префиксу <b>@{prefix}</b>",
        "mode_random": "случайные (<b>{length} символов</b>)",
    }

    strings_ru = dict(strings)

    USERNAME_RE = re.compile(r"^[A-Za-z0-9_]+$")
    PREFIX_RE = re.compile(r"^[A-Za-z0-9_]+$")

    RANDOM_USERNAME_LENGTH = 5
    RANDOM_CANDIDATES = 25
    MAX_RANDOM_CANDIDATES = 1000
    MAX_PREFIX_CANDIDATES = 500
    RESULTS_PER_PAGE = 5
    FALLBACK_DISPLAY_FOUND = 25
    PROGRESS_PREVIEW_LIMIT = 10
    PROGRESS_EVERY = 5
    PROGRESS_MIN_INTERVAL = 2.5
    SEARCH_DELAY_MIN = 0.25
    SEARCH_DELAY_MAX = 0.55

    FRAGMENT_TIMEOUT = (5, 10)
    HTTP_USER_AGENT = "Mozilla/5.0 (HookUsername/1.1)"

    def __init__(self):
        self._find_running = False
        self._find_stop_event = None
        self._grab_lock = None
        self._last_find_results = None
        self._last_find_mode = None

    async def client_ready(self, client, db):
        self._db = db
        self._client = client
        self._find_stop_event = asyncio.Event()
        self._grab_lock = asyncio.Lock()

    @staticmethod
    def _error_signature(error: Exception) -> str:
        class_name = re.sub(
            r"(?<!^)(?=[A-Z])",
            "_",
            type(error).__name__,
        ).upper()
        parts = [class_name, str(error).upper()]
        message = getattr(error, "message", None)
        if message:
            parts.append(str(message).upper())
        return " ".join(parts)

    @classmethod
    def _extract_flood_wait(cls, error: Exception) -> int:
        seconds = getattr(error, "seconds", None)
        if isinstance(seconds, (int, float)) and seconds >= 0:
            return int(seconds)

        match = re.search(r"FLOOD_WAIT[_\s-]*(\d+)", cls._error_signature(error))
        return int(match.group(1)) if match else 0

    @classmethod
    def _classify_check_error(cls, error: Exception) -> tuple[UsernameStatus, int | None]:
        signature = cls._error_signature(error)

        if "FLOOD_WAIT" in signature:
            return UsernameStatus.FLOOD_WAIT, cls._extract_flood_wait(error)
        if "USERNAME_PURCHASE_AVAILABLE" in signature:
            return UsernameStatus.PURCHASABLE, None
        if "USERNAME_INVALID" in signature:
            return UsernameStatus.INVALID, None
        if "USERNAME_OCCUPIED" in signature:
            return UsernameStatus.UNAVAILABLE, None

        return UsernameStatus.ERROR, None

    @classmethod
    def _classify_grab_error(cls, error: Exception) -> tuple[GrabStatus, int | None]:
        signature = cls._error_signature(error)

        if "FLOOD_WAIT" in signature:
            return GrabStatus.FLOOD_WAIT, cls._extract_flood_wait(error)
        if "USERNAME_PURCHASE_AVAILABLE" in signature:
            return GrabStatus.USERNAME_PURCHASABLE, None
        if "USERNAME_OCCUPIED" in signature:
            return GrabStatus.USERNAME_TAKEN, None
        if "USERNAME_INVALID" in signature:
            return GrabStatus.USERNAME_INVALID, None
        if "CHANNELS_ADMIN_PUBLIC_TOO_MUCH" in signature:
            return GrabStatus.PUBLIC_LIMIT, None
        if "CHANNELS_TOO_MUCH" in signature:
            return GrabStatus.CHANNEL_LIMIT, None
        if "USER_RESTRICTED" in signature:
            return GrabStatus.USER_RESTRICTED, None
        if "CHAT_TITLE_EMPTY" in signature:
            return GrabStatus.BAD_TITLE, None
        if "CHAT_ABOUT_TOO_LONG" in signature:
            return GrabStatus.BAD_ABOUT, None
        if (
            "CHAT_ADMIN_REQUIRED" in signature
            or "CHANNEL_INVALID" in signature
            or "CHANNEL_PRIVATE" in signature
            or "CHAT_WRITE_FORBIDDEN" in signature
        ):
            return GrabStatus.NO_RIGHTS, None

        return GrabStatus.ERROR, None

    @staticmethod
    def _normalize_username_input(raw: str) -> str:
        """Нормализует ввод username без изменения значимых символов."""
        value = unicodedata.normalize("NFKC", str(raw or "")).strip()

        # Telegram/клавиатуры иногда оставляют невидимые format-символы
        # (zero-width space/joiner, bidi marks и т. п.). Для username они
        # не имеют смысла и только ломают локальную валидацию.
        value = "".join(
            char
            for char in value
            if unicodedata.category(char) != "Cf"
        ).strip()

        # Разрешаем привычный ввод как с @, так и без него.
        value = value.lstrip("@").strip()
        return value

    @classmethod
    def _validate_username(cls, raw: str) -> tuple[str | None, str | None]:
        username = cls._normalize_username_input(raw)

        if not username:
            return None, "empty"
        if not 5 <= len(username) <= 32:
            return None, "length"
        if not cls.USERNAME_RE.fullmatch(username):
            return None, "chars"

        return username, None

    @classmethod
    def _validate_prefix(cls, raw: str) -> str | None:
        prefix = cls._normalize_username_input(raw)

        if not prefix or len(prefix) > 31:
            return None
        if not cls.PREFIX_RE.fullmatch(prefix):
            return None

        return prefix

    async def _check(self, username: str) -> tuple[UsernameStatus, int | None]:
        try:
            available = await self._client(
                functions.account.CheckUsernameRequest(username=username)
            )
            return (
                UsernameStatus.AVAILABLE if available else UsernameStatus.UNAVAILABLE,
                None,
            )
        except Exception as error:
            status, wait = self._classify_check_error(error)
            if status is UsernameStatus.ERROR:
                logger.exception("Ошибка при проверке юзернейма @%s", username)
            elif status is UsernameStatus.FLOOD_WAIT:
                logger.warning(
                    "FloodWait %ss при проверке @%s",
                    wait or 0,
                    username,
                )
            return status, wait

    @classmethod
    def _check_fragment_sync(
        cls,
        username: str,
    ) -> tuple[FragmentStatus, str | None]:
        url = f"https://fragment.com/username/{username}"
        try:
            with requests.get(
                url,
                timeout=cls.FRAGMENT_TIMEOUT,
                headers={"User-Agent": cls.HTTP_USER_AGENT},
                allow_redirects=True,
            ) as response:
                if response.status_code == 404:
                    return FragmentStatus.NOT_FOUND, None
                if response.status_code != 200:
                    logger.warning(
                        "Fragment вернул HTTP %s для @%s",
                        response.status_code,
                        username,
                    )
                    return FragmentStatus.ERROR, None
                content = response.content

            soup = BeautifulSoup(content, "html.parser")
            header_status = soup.find(class_="tm-section-header-status")
            if not header_status:
                logger.warning(
                    "Не найден статус Fragment на странице @%s",
                    username,
                )
                return FragmentStatus.ERROR, None

            status_text = header_status.get_text(" ", strip=True).lower()
            price_el = soup.select_one(".tm-value")
            price = price_el.get_text(" ", strip=True) if price_el else None

            if "sold" in status_text:
                return FragmentStatus.SOLD, price
            if "available" in status_text or "auction" in status_text:
                return FragmentStatus.AVAILABLE, price

            return FragmentStatus.NOT_FOUND, None
        except requests.RequestException as error:
            logger.warning(
                "Ошибка HTTP при проверке Fragment @%s: %s",
                username,
                error,
            )
            return FragmentStatus.ERROR, None
        except Exception:
            logger.exception("Ошибка парсинга Fragment для @%s", username)
            return FragmentStatus.ERROR, None

    async def _check_fragment(
        self,
        username: str,
    ) -> tuple[FragmentStatus, str | None]:
        return await utils.run_sync(self._check_fragment_sync, username)

    async def _cleanup_service_messages(self, channel) -> None:
        try:
            async for message in self._client.iter_messages(channel, limit=10):
                if not message.action:
                    continue
                try:
                    await message.delete()
                except Exception as error:
                    logger.debug(
                        "Не удалось удалить сервисное сообщение %s: %s",
                        getattr(message, "id", "?"),
                        error,
                    )
        except Exception as error:
            logger.debug("Не удалось очистить сервисные сообщения: %s", error)

    async def _rollback_channel(self, channel) -> bool:
        try:
            await self._client(
                functions.channels.DeleteChannelRequest(channel=channel)
            )
            return True
        except Exception:
            logger.exception("Не удалось удалить временный канал после ошибки захвата")
            return False

    async def _grab_username(
        self,
        username: str,
    ) -> tuple[GrabStatus, str | int | None, bool]:
        channel = None

        try:
            result = await self._client(
                functions.channels.CreateChannelRequest(
                    title=f"@{username}",
                    about="",
                    broadcast=True,
                    megagroup=False,
                )
            )

            chats = getattr(result, "chats", None)
            if not chats:
                logger.error("CreateChannelRequest вернул результат без chats")
                return GrabStatus.ERROR, None, False

            channel = chats[0]
            update_result = await self._client(
                functions.channels.UpdateUsernameRequest(
                    channel=channel,
                    username=username,
                )
            )
            if not update_result:
                logger.error("UpdateUsernameRequest вернул False для @%s", username)
                rollback_failed = not await self._rollback_channel(channel)
                return GrabStatus.ERROR, None, rollback_failed
        except Exception as error:
            status, detail = self._classify_grab_error(error)
            if status is GrabStatus.ERROR:
                logger.exception("Ошибка при захвате @%s", username)
            else:
                logger.warning(
                    "Не удалось занять @%s: %s",
                    username,
                    type(error).__name__,
                )

            rollback_failed = False
            if channel is not None:
                rollback_failed = not await self._rollback_channel(channel)

            return status, detail, rollback_failed

        await self._cleanup_service_messages(channel)

        return GrabStatus.SUCCESS, f"t.me/{username}", False

    def _generate_variants(self, prefix: str) -> list[str]:
        """Генерирует до MAX_PREFIX_CANDIDATES вариантов по префиксу."""
        variants: list[str] = []
        seen: set[str] = set()

        def add(candidate: str) -> None:
            if len(variants) >= self.MAX_PREFIX_CANDIDATES:
                return
            if candidate in seen:
                return
            if not 5 <= len(candidate) <= 32:
                return
            if not self.USERNAME_RE.fullmatch(candidate):
                return

            seen.add(candidate)
            variants.append(candidate)

        # Сам префикс тоже может уже быть валидным свободным юзернеймом.
        add(prefix)

        # Сначала проверяем короткие и привычные варианты.
        priority_suffixes = [
            "_",
            "__",
            "x",
            "xx",
            "official",
            "real",
            "pro",
            "me",
            "its",
            "im",
            "ok",
            "hi",
            "gg",
            "tv",
            "xo",
            "neo",
            "one",
            "go",
            "top",
            "best",
            "dev",
            "app",
            "web",
            "bot",
        ] + list("0123456789")

        for suffix in priority_suffixes:
            add(f"{prefix}{suffix}")

        # Большой пул разных хвостов. Он перемешивается, чтобы при раннем
        # FloodWait разные запуски успевали проверить разные типы вариантов.
        alphabet = "abcdefghijklmnopqrstuvwxyz"
        suffix_pool: list[str] = []

        # Числа с обычной и фиксированной длиной: 7, 42, 007, 123 и т.п.
        suffix_pool.extend(str(number) for number in range(1000))
        suffix_pool.extend(f"{number:02d}" for number in range(100))
        suffix_pool.extend(f"{number:03d}" for number in range(300))

        # Короткие буквенные хвосты: a-z и aa-zz.
        suffix_pool.extend(alphabet)
        suffix_pool.extend(
            first + second
            for first in alphabet
            for second in alphabet
        )

        # Варианты с разделителем или коротким маркером.
        suffix_pool.extend(f"_{number}" for number in range(200))
        suffix_pool.extend(f"x{number}" for number in range(200))
        suffix_pool.extend(f"_{letter}" for letter in alphabet)
        suffix_pool.extend(f"x{letter}" for letter in alphabet)

        random.shuffle(suffix_pool)

        for suffix in suffix_pool:
            if len(variants) >= self.MAX_PREFIX_CANDIDATES:
                break
            add(f"{prefix}{suffix}")

        return variants

    def _generate_random_usernames(
        self,
        length: int = 5,
        count: int = 25,
    ) -> list[str]:
        """Генерирует читаемые случайные юзернеймы."""
        vowels = "aeiou"
        consonants = "bcdfghjklmnpqrstvwxyz"
        patterns = ["cvcvc", "vcvcv", "cvvcc", "ccvcv"]

        result = []
        seen = set()
        attempts = 0
        max_attempts = max(1000, count * 40)

        while len(result) < count and attempts < max_attempts:
            attempts += 1
            if length == 5:
                pattern = random.choice(patterns)
            else:
                first = random.choice(("c", "v"))
                pattern = "".join(
                    first if index % 2 == 0 else ("v" if first == "c" else "c")
                    for index in range(length)
                )

            username = "".join(
                random.choice(consonants if kind == "c" else vowels)
                for kind in pattern
            )
            if username in seen:
                continue
            seen.add(username)
            result.append(username)

        return result

    async def _edit_status(self, status_message, message: Message, text: str):
        """Надёжно обновляет одно status-сообщение и не роняет поиск при ошибке edit."""
        target = status_message or message

        try:
            updated = await utils.answer(target, text)
            return updated or target
        except Exception as error:
            logger.warning("Не удалось обновить статус поиска: %s", error)

        if target is not message:
            try:
                updated = await utils.answer(message, text)
                return updated or message
            except Exception as error:
                logger.warning("Не удалось восстановить статус поиска: %s", error)

        return status_message

    async def _wait_search_delay(self, delay: float) -> bool:
        if self._find_stop_event is None:
            await asyncio.sleep(delay)
            return False

        try:
            await asyncio.wait_for(self._find_stop_event.wait(), timeout=delay)
            return True
        except asyncio.TimeoutError:
            return False

    async def _show_unavailable_result(
        self,
        message: Message,
        username: str,
        telegram_status: UsernameStatus,
    ) -> None:
        safe_username = html.escape(username, quote=True)
        loading = await self.inline.form(
            text=self.strings["checking"].format(username=safe_username),
            message=message,
            reply_markup=[[
                {"text": self.strings["close_button"], "callback": self._close_cb}
            ]],
        )
        inline_loading = bool(loading)
        if not loading:
            loading = await utils.answer(
                message,
                self.strings["checking"].format(username=safe_username),
            )

        fragment_status, price = await self._check_fragment(username)
        fragment_url = f"https://fragment.com/username/{username}"
        safe_url = html.escape(fragment_url, quote=True)
        safe_price = html.escape(str(price), quote=True) if price else ""
        price_line = (
            self.strings["price_line"].format(price=safe_price)
            if price
            else ""
        )

        if fragment_status is FragmentStatus.SOLD:
            text = self.strings["fragment_sold"].format(
                username=safe_username,
                price_line=price_line,
                url=safe_url,
            )
        elif fragment_status is FragmentStatus.AVAILABLE:
            text = self.strings["fragment_available"].format(
                username=safe_username,
                price_line=price_line,
                url=safe_url,
            )
        elif telegram_status is UsernameStatus.PURCHASABLE:
            text = self.strings["purchasable"].format(username=safe_username)
            if fragment_status is FragmentStatus.ERROR:
                text += self.strings["fragment_error"]
        else:
            text = self.strings["occupied"].format(username=safe_username)
            if fragment_status is FragmentStatus.ERROR:
                text += self.strings["fragment_error"]

        if inline_loading:
            try:
                await loading.edit(
                    text=text,
                    reply_markup=[[
                        {
                            "text": self.strings["close_button"],
                            "callback": self._close_cb,
                        }
                    ]],
                )
                return
            except Exception as error:
                logger.warning("Не удалось обновить inline-результат: %s", error)

        await self._edit_status(loading, message, text)

    @loader.command(
        ru_doc="<юзернейм> — проверяет доступность юзернейма с возможностью занять его"
    )
    async def z(self, message: Message):
        """<юзернейм> - проверяет доступность юзернейма с возможностью занять его."""
        raw_args = utils.get_args_raw(message)
        username, error = self._validate_username(raw_args)

        if error == "empty":
            await utils.answer(message, self.strings["no_args"])
            return
        if error == "length":
            await utils.answer(message, self.strings["bad_length"])
            return
        if error == "chars" or username is None:
            await utils.answer(message, self.strings["bad_chars"])
            return

        status, wait = await self._check(username)
        safe_username = html.escape(username, quote=True)

        if status is UsernameStatus.AVAILABLE:
            form = await self.inline.form(
                text=self.strings["available"].format(username=safe_username),
                message=message,
                reply_markup=[[
                    {
                        "text": self.strings["grab_button"],
                        "callback": self._grab_cb,
                        "args": (username,),
                    },
                    {"text": "✖", "callback": self._close_cb},
                ]],
            )
            if not form:
                await utils.answer(
                    message,
                    self.strings["available_no_inline"].format(
                        username=safe_username
                    ),
                )
            return

        if status is UsernameStatus.FLOOD_WAIT:
            await utils.answer(
                message,
                self.strings["flood_wait"].format(seconds=max(wait or 0, 1)),
            )
            return

        if status is UsernameStatus.ERROR:
            await utils.answer(
                message,
                self.strings["check_error"].format(username=safe_username),
            )
            return

        if status is UsernameStatus.INVALID:
            await utils.answer(message, self.strings["bad_chars"])
            return

        await self._show_unavailable_result(message, username, status)

    def _build_find_page(
        self,
        usernames: tuple[str, ...],
        page: int,
        mode_text: str,
    ) -> tuple[str, list[list[dict]]]:
        """Собирает одну страницу результатов поиска и inline-кнопки."""
        if not usernames:
            return self.strings["find_page_empty"], [[{
                "text": self.strings["close_button"],
                "callback": self._close_cb,
            }]]

        pages = max(
            1,
            (len(usernames) + self.RESULTS_PER_PAGE - 1)
            // self.RESULTS_PER_PAGE,
        )
        page = max(0, min(page, pages - 1))
        start = page * self.RESULTS_PER_PAGE
        page_items = usernames[start : start + self.RESULTS_PER_PAGE]

        lines = "\n".join(
            f"• <code>@{html.escape(username)}</code>"
            for username in page_items
        )

        buttons = [
            [{
                "text": f"@{username}",
                "callback": self._grab_cb,
                "args": (username,),
            }]
            for username in page_items
        ]

        if pages > 1:
            navigation = []

            if page > 0:
                navigation.append({
                    "text": "◀️",
                    "callback": self._find_page_cb,
                    "args": (usernames, page - 1, mode_text),
                })

            navigation.append({
                "text": f"{page + 1}/{pages}",
                "callback": self._find_page_cb,
                "args": (usernames, page, mode_text),
            })

            if page + 1 < pages:
                navigation.append({
                    "text": "▶️",
                    "callback": self._find_page_cb,
                    "args": (usernames, page + 1, mode_text),
                })

            buttons.append(navigation)

        buttons.append([{
            "text": self.strings["close_button"],
            "callback": self._close_cb,
        }])

        text = self.strings["find_result"].format(
            mode=mode_text,
            lines=lines,
            page=page + 1,
            pages=pages,
            total_found=len(usernames),
        )
        return text, buttons

    async def _find_page_cb(
        self,
        call,
        usernames: tuple[str, ...],
        page: int,
        mode_text: str,
    ):
        """Переключает страницу найденных юзернеймов."""
        try:
            await call.answer()
        except Exception as error:
            logger.debug(
                "Не удалось подтвердить callback пагинации: %s",
                error,
            )

        if not usernames:
            try:
                await call.edit(
                    text=self.strings["find_page_empty"],
                    reply_markup=[[{
                        "text": self.strings["close_button"],
                        "callback": self._close_cb,
                    }]],
                )
            except Exception:
                logger.exception(
                    "Не удалось показать пустую страницу результатов"
                )
            return

        text, buttons = self._build_find_page(
            tuple(usernames),
            int(page),
            mode_text,
        )

        try:
            await call.edit(
                text=text,
                reply_markup=buttons,
            )
        except Exception:
            logger.exception(
                "Не удалось переключить страницу результатов"
            )

    @loader.command(
        ru_doc=(
            "[кол-во] <префикс|длина> — ищет свободные юзернеймы. "
            "Без аргументов — 25 случайных по 5 символов; "
            "одно число — длина случайных (500 шт.); "
            "текст — варианты по префиксу; "
            "два аргумента: количество + префикс или длина"
        )
    )
    async def zfind(self, message: Message):
        """[кол-во] <префикс|длина> — ищет свободные юзернеймы."""
        raw_args = utils.get_args_raw(message).strip()
        args = raw_args.split() if raw_args else []

        if self._find_running:
            await utils.answer(message, self.strings["find_running"])
            return

        if not args:
            length = self.RANDOM_USERNAME_LENGTH
            candidates = self._generate_random_usernames(
                length=length,
                count=self.RANDOM_CANDIDATES,
            )
            mode_text = self.strings["mode_random"].format(length=length)

        elif len(args) == 1:
            arg = args[0]
            if arg.isdigit():
                length = int(arg)
                if not 5 <= length <= 32:
                    await utils.answer(message, self.strings["bad_length"])
                    return
                candidates = self._generate_random_usernames(
                    length=length,
                    count=self.MAX_PREFIX_CANDIDATES,
                )
                mode_text = self.strings["mode_random"].format(length=length)
            else:
                prefix = self._validate_prefix(arg)
                if prefix is None:
                    await utils.answer(message, self.strings["prefix_bad"])
                    return
                candidates = self._generate_variants(prefix)
                safe_prefix = html.escape(prefix, quote=True)
                mode_text = self.strings["mode_prefix"].format(prefix=safe_prefix)
                if not candidates:
                    await utils.answer(
                        message,
                        self.strings["find_nothing"].format(mode=mode_text),
                    )
                    return

        else:
            check_count_str, second = args[0], args[1]
            if not check_count_str.isdigit():
                await utils.answer(message, self.strings["prefix_bad"])
                return
            check_count = int(check_count_str)
            if not 1 <= check_count <= self.MAX_RANDOM_CANDIDATES:
                await utils.answer(
                    message,
                    self.strings["count_bad"].format(
                        maximum=self.MAX_RANDOM_CANDIDATES
                    ),
                )
                return

            if second.isdigit():
                length = int(second)
                if not 5 <= length <= 32:
                    await utils.answer(message, self.strings["bad_length"])
                    return
                candidates = self._generate_random_usernames(
                    length=length,
                    count=check_count,
                )
                mode_text = self.strings["mode_random"].format(length=length)
            else:
                prefix = self._validate_prefix(second)
                if prefix is None:
                    await utils.answer(message, self.strings["prefix_bad"])
                    return
                candidates = self._generate_variants(prefix)
                # clamp to requested count
                if len(candidates) > check_count:
                    candidates = candidates[:check_count]
                safe_prefix = html.escape(prefix, quote=True)
                mode_text = self.strings["mode_prefix"].format(prefix=safe_prefix)
                if not candidates:
                    await utils.answer(
                        message,
                        self.strings["find_nothing"].format(mode=mode_text),
                    )
                    return

        self._find_running = True
        if self._find_stop_event is None:
            self._find_stop_event = asyncio.Event()
        self._find_stop_event.clear()

        status_message = None
        found = []
        found_count = 0
        checked = 0
        stop_reason = None
        flood_wait = 0
        last_progress_update = time.monotonic()

        form = None
        status_message = None
        inline_mode = False

        try:
            form = await self.inline.form(
                text=self.strings["find_start"].format(
                    mode=mode_text,
                    total=len(candidates),
                ),
                message=message,
                reply_markup=[[
                    {"text": self.strings["stop_button"], "callback": self._stop_cb},
                ]],
            )
            if form:
                inline_mode = True
            else:
                status_message = await utils.answer(
                    message,
                    self.strings["find_start"].format(
                        mode=mode_text,
                        total=len(candidates),
                    ),
                )

            for index, username in enumerate(candidates):
                if self._find_stop_event.is_set():
                    stop_reason = "user"
                    break

                status, wait = await self._check(username)
                checked = index + 1

                if status is UsernameStatus.AVAILABLE:
                    found_count += 1
                    found.append(username)
                elif status is UsernameStatus.FLOOD_WAIT:
                    stop_reason = "flood"
                    flood_wait = max(wait or 0, 1)
                    break
                elif status is UsernameStatus.ERROR:
                    stop_reason = "error"
                    break

                now = time.monotonic()
                if (
                    checked % self.PROGRESS_EVERY == 0
                    and now - last_progress_update >= self.PROGRESS_MIN_INTERVAL
                ):
                    preview_items = found[: self.PROGRESS_PREVIEW_LIMIT]
                    found_preview = (
                        "\n".join(
                            f"• @{html.escape(item)}" for item in preview_items
                        )
                        if preview_items
                        else self.strings["find_preview_empty"]
                    )
                    progress_text = self.strings["find_progress"].format(
                        mode=mode_text,
                        checked=checked,
                        total=len(candidates),
                        found_count=found_count,
                        preview=found_preview,
                    )
                    if inline_mode:
                        try:
                            await form.edit(
                                text=progress_text,
                                reply_markup=[[
                                    {"text": self.strings["stop_button"], "callback": self._stop_cb},
                                ]],
                            )
                        except Exception as error:
                            logger.warning("Не удалось обновить inline-прогресс: %s", error)
                            inline_mode = False
                            status_message = await utils.answer(message, progress_text)
                    else:
                        status_message = await self._edit_status(
                            status_message,
                            message,
                            progress_text,
                        )
                    last_progress_update = now

                if index + 1 < len(candidates):
                    stopped = await self._wait_search_delay(
                        random.uniform(
                            self.SEARCH_DELAY_MIN,
                            self.SEARCH_DELAY_MAX,
                        )
                    )
                    if stopped:
                        stop_reason = "user"
                        break

            if stop_reason == "flood":
                flood_text = self.strings["find_flood"].format(
                    checked=checked,
                    total=len(candidates),
                    seconds=flood_wait,
                )
                if inline_mode:
                    try:
                        await form.edit(
                            text=flood_text,
                            reply_markup=[[
                                {"text": self.strings["stop_button"], "callback": self._stop_cb},
                            ]],
                        )
                    except Exception:
                        await utils.answer(message, flood_text)
                else:
                    await self._edit_status(status_message, message, flood_text)
                return

            if stop_reason == "error":
                error_text = self.strings["find_error"].format(
                    checked=checked,
                    total=len(candidates),
                )
                if inline_mode:
                    try:
                        await form.edit(
                            text=error_text,
                            reply_markup=[[
                                {"text": self.strings["stop_button"], "callback": self._stop_cb},
                            ]],
                        )
                    except Exception:
                        await utils.answer(message, error_text)
                else:
                    await self._edit_status(status_message, message, error_text)
                return

            if stop_reason == "user":
                if found_count > 0:
                    found_tuple = tuple(found)
                    self._last_find_results = found_tuple
                    self._last_find_mode = mode_text
                    page_text, page_buttons = self._build_find_page(
                        found_tuple,
                        0,
                        mode_text,
                    )
                    stop_prefix = self.strings["find_stopped"].format(
                        checked=checked,
                        total=len(candidates),
                        found_count=found_count,
                    )
                    full_text = stop_prefix + "\n\n" + page_text
                    if inline_mode:
                        try:
                            await form.edit(text=full_text, reply_markup=page_buttons)
                        except Exception:
                            logger.exception("Не удалось показать результаты остановленного поиска")
                            await utils.answer(message, full_text)
                    else:
                        await self._edit_status(status_message, message, full_text)
                else:
                    stop_text = self.strings["find_stopped"].format(
                        checked=checked,
                        total=len(candidates),
                        found_count=found_count,
                    )
                    if inline_mode:
                        try:
                            await form.edit(
                                text=stop_text,
                                reply_markup=[[
                                    {"text": self.strings["stop_button"], "callback": self._stop_cb},
                                ]],
                            )
                        except Exception:
                            await utils.answer(message, stop_text)
                    else:
                        await self._edit_status(status_message, message, stop_text)
                return

            if found_count == 0:
                nothing_text = self.strings["find_nothing"].format(mode=mode_text)
                if inline_mode:
                    try:
                        await form.edit(
                            text=nothing_text,
                            reply_markup=[[
                                {"text": self.strings["stop_button"], "callback": self._stop_cb},
                            ]],
                        )
                    except Exception:
                        await utils.answer(message, nothing_text)
                else:
                    await self._edit_status(status_message, message, nothing_text)
                return

            found_tuple = tuple(found)
            self._last_find_results = found_tuple
            self._last_find_mode = mode_text
            page_text, page_buttons = self._build_find_page(
                found_tuple,
                0,
                mode_text,
            )

            if inline_mode:
                try:
                    await form.edit(text=page_text, reply_markup=page_buttons)
                except Exception:
                    logger.exception("Не удалось показать результаты в inline-форме")
                    await utils.answer(message, page_text)
            else:
                result_form = await self.inline.form(
                    text=page_text,
                    message=message,
                    reply_markup=page_buttons,
                )
                if result_form and status_message is not None:
                    try:
                        await status_message.delete()
                    except Exception as error:
                        logger.debug(
                            "Не удалось удалить сообщение прогресса после поиска: %s",
                            error,
                        )

                if not result_form:
                    fallback_items = found[: self.FALLBACK_DISPLAY_FOUND]
                    fallback_lines = "\n".join(
                        f"• <code>@{html.escape(username)}</code>"
                        for username in fallback_items
                    )
                    more_line = (
                        self.strings["find_more"].format(
                            shown=len(fallback_items),
                            total_found=found_count,
                        )
                        if found_count > len(fallback_items)
                        else ""
                    )
                    await self._edit_status(
                        status_message,
                        message,
                        self.strings["find_result_fallback"].format(
                            mode=mode_text,
                            lines=fallback_lines,
                            more_line=more_line,
                        ),
                    )
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("Неожиданная ошибка поиска юзернеймов")
            error_text = self.strings["find_error"].format(
                checked=checked,
                total=len(candidates),
            )
            if inline_mode:
                try:
                    await form.edit(
                        text=error_text,
                        reply_markup=[[
                            {"text": self.strings["stop_button"], "callback": self._stop_cb},
                        ]],
                    )
                except Exception:
                    await utils.answer(message, error_text)
            else:
                await self._edit_status(
                    status_message,
                    message,
                    error_text,
                )
        finally:
            self._find_running = False
            if self._find_stop_event is not None:
                self._find_stop_event.clear()

    @loader.command(ru_doc="— останавливает поиск юзернеймов")
    async def zstop(self, message: Message):
        """— останавливает поиск юзернеймов."""
        if self._find_running:
            if self._find_stop_event is not None:
                self._find_stop_event.set()
            await utils.answer(message, self.strings["stop_ok"])
        else:
            await utils.answer(message, self.strings["stop_idle"])

    def _grab_error_text(self, status: GrabStatus, detail: str | int | None) -> str:
        if status is GrabStatus.USERNAME_TAKEN:
            return self.strings["grab_taken"]
        if status is GrabStatus.USERNAME_INVALID:
            return self.strings["grab_invalid"]
        if status is GrabStatus.USERNAME_PURCHASABLE:
            return self.strings["grab_purchasable"]
        if status is GrabStatus.FLOOD_WAIT:
            return self.strings["grab_flood"].format(
                seconds=max(int(detail or 0), 1)
            )
        if status is GrabStatus.PUBLIC_LIMIT:
            return self.strings["grab_public_limit"]
        if status is GrabStatus.CHANNEL_LIMIT:
            return self.strings["grab_channel_limit"]
        if status is GrabStatus.USER_RESTRICTED:
            return self.strings["grab_restricted"]
        if status is GrabStatus.BAD_TITLE:
            return self.strings["grab_bad_title"]
        if status is GrabStatus.BAD_ABOUT:
            return self.strings["grab_bad_about"]
        if status is GrabStatus.NO_RIGHTS:
            return self.strings["grab_no_rights"]
        return self.strings["grab_error"]

    async def _grab_cb(self, call, username: str):
        username, error = self._validate_username(username)
        if error or username is None:
            try:
                await call.answer(self.strings["grab_invalid"], show_alert=True)
            except Exception as answer_error:
                logger.debug("Не удалось ответить на устаревший callback: %s", answer_error)
            return

        if self._grab_lock is None:
            self._grab_lock = asyncio.Lock()

        if self._grab_lock.locked():
            try:
                await call.answer(self.strings["grab_busy"], show_alert=False)
            except Exception as answer_error:
                logger.debug("Не удалось ответить на callback: %s", answer_error)
            return

        async with self._grab_lock:
            try:
                await call.answer(self.strings["grabbing"], show_alert=False)
            except Exception as answer_error:
                logger.debug("Не удалось показать статус callback: %s", answer_error)

            status, info, rollback_failed = await self._grab_username(username)
            safe_username = html.escape(username, quote=True)

            if status is GrabStatus.SUCCESS:
                safe_channel = html.escape(str(info), quote=True)
                text = self.strings["grab_success"].format(
                    username=safe_username,
                    channel=safe_channel,
                )
            else:
                error_text = html.escape(
                    self._grab_error_text(status, info),
                    quote=True,
                )
                rollback_warning = (
                    self.strings["rollback_warning"] if rollback_failed else ""
                )
                text = self.strings["grab_error_title"].format(
                    error=error_text,
                    rollback_warning=rollback_warning,
                )

            try:
                reply_markup = []
                if self._last_find_results is not None:
                    updated = tuple(
                        u for u in self._last_find_results if u != username
                    )
                    self._last_find_results = updated if updated else None
                    if updated:
                        reply_markup.append([
                            {
                                "text": self.strings["back_button"],
                                "callback": self._find_page_cb,
                                "args": (updated, 0, self._last_find_mode or ""),
                            },
                            {
                                "text": self.strings["close_button"],
                                "callback": self._close_cb,
                            },
                        ])
                if not reply_markup:
                    reply_markup = [[
                        {
                            "text": self.strings["close_button"],
                            "callback": self._close_cb,
                        }
                    ]]
                await call.edit(
                    text=text,
                    reply_markup=reply_markup,
                )
            except Exception:
                logger.exception("Не удалось обновить inline-форму после захвата")

    async def _close_cb(self, call):
        try:
            await call.delete()
        except Exception as error:
            logger.debug("Не удалось закрыть inline-форму: %s", error)

    async def _stop_cb(self, call):
        try:
            await call.answer("⛔ остановлено")
        except Exception as error:
            logger.debug("Не удалось ответить на stop callback: %s", error)
        if self._find_stop_event is not None:
            self._find_stop_event.set()
