package by.bebuliby.chrona.domain

/** No ordinary-user endpoint switching. Legacy action name remains ABI/source compatible. */
object ManagedServerConfiguration {
    const val CODE = "SERVER_CONFIGURATION_MANAGED"
    const val MESSAGE = "Адрес сервера задан сборкой приложения. Изменение адреса пользователем отключено."
    fun rejectUserEdit(): Nothing = throw CoreFailure(CODE, MESSAGE)
}
