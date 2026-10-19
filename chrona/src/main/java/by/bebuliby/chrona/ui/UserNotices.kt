package by.bebuliby.chrona.ui

import by.bebuliby.chrona.domain.UiNotice

/** Hide only recognized routine information; unknown failures still have a visible, actionable error. */
internal fun userNoticeMessage(notice: UiNotice): String? = when (notice.code) {
    "SYNC_SNAPSHOT_COMPLETE", "SHARING_SAVED", "SUCCESS", "SAVED", "LOGIN_SUCCESS", "LOGOUT_SUCCESS",
    "DISCOVERABILITY_SAVED", "SYNC_IN_PROGRESS", "SYNC_COMPLETE", "NO_CHANGES", "ALREADY_UP_TO_DATE",
    "CANCELLED", "CANCELED", "OPERATION_CANCELLED", "OPERATION_CANCELED", "LOGIN_CANCELLED", "LOGIN_CANCELED" -> null
    "NOT_READY" -> "Сервис временно недоступен. Повторите позже."
    "OFFLINE" -> "Нет связи. Проверьте интернет и повторите попытку."
    "SESSION_REFRESH_UNCERTAIN" -> "Не удалось подтвердить обновление входа после обрыва связи. Данные сохранены. В этом случае нужно повторить вход через Telegram."
    "PERMISSION_REQUIRED" -> "Разрешите отслеживание Shorts в настройках Android."
    "STORAGE_ERROR" -> "Не удалось открыть данные. Перезапустите приложение; данные не удалялись."
    "LOCAL_ERROR", "SECURE_STORAGE" -> "Не удалось сохранить данные. Повторите попытку."
    "PHOTO_PERMISSION" -> "Не удалось открыть фото. Выберите его заново."
    "VALIDATION" -> "Проверьте введённое значение."
    "NOT_FOUND" -> "Данные не найдены. Обновите экран."
    "LOGIN_REQUIRED", "LOGIN_RESPONSE_LOST" -> "Войдите через Telegram ещё раз."
    "SERVER_NOT_CONFIGURED", "INVALID_ORIGIN", "TELEGRAM_NOT_CONFIGURED" -> "Подключение недоступно. Повторите позже или обратитесь за помощью."
    "ACCOUNT_BINDING_CONFLICT", "BOOTSTRAP_EXISTING_BINDING" -> "Профиль связан с другим подключением. Обратитесь за помощью, не удаляя данные."
    "REMOTE_REVOKE_UNCONFIRMED" -> "Вы вышли на этом устройстве. Отзыв сессии на сервере пока не подтверждён."
    "REVISION_CONFLICT", "PULL_LOCAL_CONFLICT", "IDEMPOTENCY_CONFLICT", "SOCIAL_PENDING" -> "Конфликт данных. Изменения сохранены на устройстве; повторите попытку позже."
    "CONSENT_REQUIRED", "CONSENT_RECONFIRM_REQUIRED" -> "Подтвердите отправку данных в настройках синхронизации."
    "CONSENT_SCOPE_CONFLICT" -> "Подключение изменилось. Подтвердите синхронизацию заново."
    "CONSENT_DEPENDENCY_PENDING" -> "Не все записи готовы к отправке. Повторите синхронизацию позже."
    else -> "Не удалось выполнить действие. Повторите попытку."
}
