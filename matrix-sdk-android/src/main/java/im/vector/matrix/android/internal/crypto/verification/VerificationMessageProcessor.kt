/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.matrix.android.internal.crypto.verification

import im.vector.matrix.android.api.session.crypto.CryptoService
import im.vector.matrix.android.api.session.crypto.MXCryptoError
import im.vector.matrix.android.api.session.crypto.verification.VerificationService
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.LocalEcho
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.message.MessageContent
import im.vector.matrix.android.api.session.room.model.message.MessageRelationContent
import im.vector.matrix.android.api.session.room.model.message.MessageType
import im.vector.matrix.android.api.session.room.model.message.MessageVerificationReadyContent
import im.vector.matrix.android.api.session.room.model.message.MessageVerificationRequestContent
import im.vector.matrix.android.api.session.room.model.message.MessageVerificationStartContent
import im.vector.matrix.android.internal.crypto.algorithms.olm.OlmDecryptionResult
import im.vector.matrix.android.internal.database.model.EventInsertType
import im.vector.matrix.android.internal.di.DeviceId
import im.vector.matrix.android.internal.di.UserId
import im.vector.matrix.android.internal.session.EventInsertLiveProcessor
import io.realm.Realm
import timber.log.Timber
import java.util.ArrayList
import javax.inject.Inject

internal class VerificationMessageProcessor @Inject constructor(
        private val cryptoService: CryptoService,
        private val verificationService: DefaultVerificationService,
        @UserId private val userId: String,
        @DeviceId private val deviceId: String?
) : EventInsertLiveProcessor {

    private val transactionsHandledByOtherDevice = ArrayList<String>()

    private val allowedTypes = listOf(
            EventType.KEY_VERIFICATION_START,
            EventType.KEY_VERIFICATION_ACCEPT,
            EventType.KEY_VERIFICATION_KEY,
            EventType.KEY_VERIFICATION_MAC,
            EventType.KEY_VERIFICATION_CANCEL,
            EventType.KEY_VERIFICATION_DONE,
            EventType.KEY_VERIFICATION_READY,
            EventType.MESSAGE,
            EventType.ENCRYPTED
    )

    override fun shouldProcess(eventId: String, eventType: String, insertType: EventInsertType): Boolean {
        if (insertType != EventInsertType.INCREMENTAL_SYNC) {
            return false
        }
        return allowedTypes.contains(eventType) && !LocalEcho.isLocalEchoId(eventId)
    }

    override suspend fun process(realm: Realm, event: Event) {
        Timber.v("## SAS Verification live observer: received msgId: ${event.eventId} msgtype: ${event.type} from ${event.senderId}")

        // If the request is in the future by more than 5 minutes or more than 10 minutes in the past,
        // the message should be ignored by the receiver.

        if (!VerificationService.isValidRequest(event.ageLocalTs
                        ?: event.originServerTs)) return Unit.also {
            Timber.d("## SAS Verification live observer: msgId: ${event.eventId} is outdated")
        }

        // decrypt if needed?
        if (event.isEncrypted() && event.mxDecryptionResult == null) {
            // TODO use a global event decryptor? attache to session and that listen to new sessionId?
            // for now decrypt sync
            try {
                val result = cryptoService.decryptEvent(event, "")
                event.mxDecryptionResult = OlmDecryptionResult(
                        payload = result.clearEvent,
                        senderKey = result.senderCurve25519Key,
                        keysClaimed = result.claimedEd25519Key?.let { mapOf("ed25519" to it) },
                        forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain
                )
            } catch (e: MXCryptoError) {
                Timber.e("## SAS Failed to decrypt event: ${event.eventId}")
                verificationService.onPotentiallyInterestingEventRoomFailToDecrypt(event)
            }
        }
        Timber.v("## SAS Verification live observer: received msgId: ${event.eventId} type: ${event.getClearType()}")

        // Relates to is not encrypted
        val relatesToEventId = event.content.toModel<MessageRelationContent>()?.relatesTo?.eventId

        if (event.senderId == userId) {
            // If it's send from me, we need to keep track of Requests or Start
            // done from another device of mine

            if (EventType.MESSAGE == event.getClearType()) {
                val msgType = event.getClearContent().toModel<MessageContent>()?.msgType
                if (MessageType.MSGTYPE_VERIFICATION_REQUEST == msgType) {
                    event.getClearContent().toModel<MessageVerificationRequestContent>()?.let {
                        if (it.fromDevice != deviceId) {
                            // The verification is requested from another device
                            Timber.v("## SAS Verification live observer: Transaction requested from other device  tid:${event.eventId} ")
                            event.eventId?.let { txId -> transactionsHandledByOtherDevice.add(txId) }
                        }
                    }
                }
            } else if (EventType.KEY_VERIFICATION_START == event.getClearType()) {
                event.getClearContent().toModel<MessageVerificationStartContent>()?.let {
                    if (it.fromDevice != deviceId) {
                        // The verification is started from another device
                        Timber.v("## SAS Verification live observer: Transaction started by other device  tid:$relatesToEventId ")
                        relatesToEventId?.let { txId -> transactionsHandledByOtherDevice.add(txId) }
                        verificationService.onRoomRequestHandledByOtherDevice(event)
                    }
                }
            } else if (EventType.KEY_VERIFICATION_READY == event.getClearType()) {
                event.getClearContent().toModel<MessageVerificationReadyContent>()?.let {
                    if (it.fromDevice != deviceId) {
                        // The verification is started from another device
                        Timber.v("## SAS Verification live observer: Transaction started by other device  tid:$relatesToEventId ")
                        relatesToEventId?.let { txId -> transactionsHandledByOtherDevice.add(txId) }
                        verificationService.onRoomRequestHandledByOtherDevice(event)
                    }
                }
            } else if (EventType.KEY_VERIFICATION_CANCEL == event.getClearType() || EventType.KEY_VERIFICATION_DONE == event.getClearType()) {
                relatesToEventId?.let {
                    transactionsHandledByOtherDevice.remove(it)
                    verificationService.onRoomRequestHandledByOtherDevice(event)
                }
            }

            Timber.v("## SAS Verification ignoring message sent by me: ${event.eventId} type: ${event.getClearType()}")
            return
        }

        if (relatesToEventId != null && transactionsHandledByOtherDevice.contains(relatesToEventId)) {
            // Ignore this event, it is directed to another of my devices
            Timber.v("## SAS Verification live observer: Ignore Transaction handled by other device  tid:$relatesToEventId ")
            return
        }
        when (event.getClearType()) {
            EventType.KEY_VERIFICATION_START,
            EventType.KEY_VERIFICATION_ACCEPT,
            EventType.KEY_VERIFICATION_KEY,
            EventType.KEY_VERIFICATION_MAC,
            EventType.KEY_VERIFICATION_CANCEL,
            EventType.KEY_VERIFICATION_READY,
            EventType.KEY_VERIFICATION_DONE -> {
                verificationService.onRoomEvent(event)
            }
            EventType.MESSAGE               -> {
                if (MessageType.MSGTYPE_VERIFICATION_REQUEST == event.getClearContent().toModel<MessageContent>()?.msgType) {
                    verificationService.onRoomRequestReceived(event)
                }
            }
        }
    }
}
