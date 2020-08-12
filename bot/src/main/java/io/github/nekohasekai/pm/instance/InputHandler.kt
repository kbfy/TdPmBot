package io.github.nekohasekai.pm.instance

import cn.hutool.core.date.SystemClock
import io.github.nekohasekai.nekolib.core.client.TdException
import io.github.nekohasekai.nekolib.core.client.TdHandler
import io.github.nekohasekai.nekolib.core.raw.getChat
import io.github.nekohasekai.nekolib.core.raw.getUser
import io.github.nekohasekai.nekolib.core.utils.*
import io.github.nekohasekai.pm.INPUT_NOTICE
import io.github.nekohasekai.pm.INTEGRATION_PAUSED_NOTICE
import io.github.nekohasekai.pm.Launcher
import io.github.nekohasekai.pm.database.MessageRecord
import io.github.nekohasekai.pm.database.MessageRecord.Companion.MESSAGE_TYPE_INPUT_FORWARDED
import io.github.nekohasekai.pm.database.MessageRecord.Companion.MESSAGE_TYPE_INPUT_MESSAGE
import io.github.nekohasekai.pm.database.MessageRecord.Companion.MESSAGE_TYPE_INPUT_NOTICE
import io.github.nekohasekai.pm.database.PmInstance
import io.github.nekohasekai.pm.manage.SetIntegration
import td.TdApi
import java.util.concurrent.atomic.AtomicBoolean

class InputHandler(pmInstance: PmInstance) : TdHandler(), PmInstance by pmInstance {

    private var currentUser = 0
    private var times = 0
    private var inited by AtomicBoolean()

    override suspend fun onNewMessage(userId: Int, chatId: Long, message: TdApi.Message) {

        val integration = integration

        if (admin == chatId || chatId == integration?.integration || !message.fromPrivate) return

        if (!inited) {

            getChat(admin)

            inited = true

        }

        database {

            MessageRecord.new(message.id) {

                type = MESSAGE_TYPE_INPUT_MESSAGE

                this.chatId = chatId

                botId = me.id

                createAt = (SystemClock.now() / 100L).toInt()

            }

        }

        suspend fun MessageFactory.syncToTarget(): TdApi.Message? {

            if (integration != null && !integration.paused) {

                try {

                    return this syncTo integration.integration

                } catch (e: TdException) {

                    defaultLog.warn(e)

                    database.write {

                        integration.paused = true
                        integration.flush()

                    }

                    Launcher make L.INTEGRATION_PAUSED_NOTICE syncTo admin

                    Launcher.findHandler<SetIntegration>().startSet(L, me.id, me.username, admin.toInt(), admin, 0L, true)

                }

            }

            try {

                return this syncTo admin

            } catch (e: TdException) {

                defaultLog.warn(e, "banned by owner")

                // TODO: PROCESS BOT BANNED BY OWNER

            }

            return null

        }

        if (userId != currentUser || times < 1) {

            val user = getUser(userId)

            val inputNotice = (sudo makeHtml L.INPUT_NOTICE.input(user.asIdMention, user.displayNameHtml)).syncToTarget()
                    ?: return

            database {

                MessageRecord.new(inputNotice.id) {

                    type = MESSAGE_TYPE_INPUT_NOTICE

                    this.chatId = chatId

                    botId = me.id

                    createAt = (SystemClock.now() / 100L).toInt()

                }

            }

            currentUser = userId
            times = 4

        } else {

            times--

        }

        val forwardedMessage = (sudo makeForward message).syncToTarget() ?: return

        database {

            MessageRecord.new(forwardedMessage.id) {

                type = MESSAGE_TYPE_INPUT_FORWARDED

                this.chatId = chatId

                targetId = message.id

                botId = me.id

                createAt = (SystemClock.now() / 100L).toInt()

            }

        }

        finishEvent()

    }

}