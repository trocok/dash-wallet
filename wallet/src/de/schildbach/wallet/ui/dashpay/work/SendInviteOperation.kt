/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.schildbach.wallet.ui.dashpay.work

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.work.*
import com.google.firebase.crashlytics.FirebaseCrashlytics
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.security.SecurityGuard
import java.util.*

class SendInviteOperation(val application: Application) {

    class SendInviteOperationException(message: String) : java.lang.Exception(message)

    companion object {
        const val WORK_NAME = "SendInvite.WORK#"

        fun uniqueWorkName(toUserId: String) = WORK_NAME + toUserId

        fun operationStatus(application: Application, toUserId: String): LiveData<Resource<Pair<String, String>>> {
            val workManager: WorkManager = WorkManager.getInstance(application)
            return workManager.getWorkInfosForUniqueWorkLiveData(uniqueWorkName(toUserId)).switchMap {
                return@switchMap liveData {

                    if (it.isNullOrEmpty()) {
                        return@liveData
                    }

                    if (it.size > 1) {
                        val e = RuntimeException("there should never be more than one unique work ${uniqueWorkName(toUserId)}")
                        FirebaseCrashlytics.getInstance().recordException(e)
                        throw e
                    }

                    val workInfo = it[0]
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val userIdOut = SendContactRequestWorker.extractUserId(workInfo.outputData)!!
                            val toUserIdOut = SendContactRequestWorker.extractToUserId(workInfo.outputData)!!
                            emit(Resource.success(Pair(userIdOut, toUserIdOut)))
                        }
                        WorkInfo.State.FAILED -> {
                            val errorMessage = BaseWorker.extractError(workInfo.outputData)
                            emit(if (errorMessage != null) {
                                val exception = SendInviteOperationException(errorMessage)
                                FirebaseCrashlytics.getInstance().recordException(exception)
                                Resource.error(errorMessage, null)
                            } else {
                                val exception = SendInviteOperationException("Unknown error")
                                FirebaseCrashlytics.getInstance().recordException(exception)
                                Resource.error(exception)
                            })
                        }
                        WorkInfo.State.CANCELLED -> {
                            emit(Resource.canceled(null))
                        }
                        else -> {
                            emit(Resource.loading(null))
                        }
                    }
                }
            }
        }

        fun allOperationsStatus(application: Application): LiveData<MutableMap<String, Resource<WorkInfo>>> {
            val workManager: WorkManager = WorkManager.getInstance(application)
            return workManager.getWorkInfosByTagLiveData(SendContactRequestWorker::class.qualifiedName!!).switchMap {
                return@switchMap liveData {

                    if (it.isNullOrEmpty()) {
                        return@liveData
                    }

                    val result = mutableMapOf<String, Resource<WorkInfo>>()
                    it.forEach {
                        var toUserId = ""
                        it.tags.forEach { tag ->
                            if (tag.startsWith("invite:")) {
                                toUserId = tag.replace("invite:", "")
                            }
                        }
                        result[toUserId] = convertState(it)
                    }
                    emit(result)
                }
            }
        }

        private fun convertState(workInfo: WorkInfo): Resource<WorkInfo> {
            return when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> {
                    Resource.success(workInfo)
                }
                WorkInfo.State.FAILED -> {
                    val errorMessage = BaseWorker.extractError(workInfo.outputData)
                    if (errorMessage != null) {
                        Resource.error(errorMessage, workInfo)
                    } else {
                        Resource.error(Exception(), workInfo)
                    }
                }
                WorkInfo.State.CANCELLED -> {
                    Resource.canceled(workInfo)
                }
                else -> {
                    Resource.loading(workInfo)
                }
            }
        }
    }

    private val workManager: WorkManager = WorkManager.getInstance(application)

    /**
     * Gets the list of all SendContactRequestWorker WorkInfo's
     */
    val allOperationsData = workManager.getWorkInfosByTagLiveData(SendInviteWorker::class.qualifiedName!!)

    @SuppressLint("EnqueueWork")
    fun create(): WorkContinuation {

        val password = SecurityGuard().retrievePassword()
        val id = "invite-${UUID.randomUUID()}"
        val sendInviteWorker = OneTimeWorkRequestBuilder<SendInviteWorker>()
                .setInputData(workDataOf(
                        SendInviteWorker.KEY_PASSWORD to password))
                .addTag("invite:$id")
                .build()

        return WorkManager.getInstance(application)
                .beginUniqueWork(uniqueWorkName(id),
                        ExistingWorkPolicy.KEEP,
                        sendInviteWorker)
    }

}