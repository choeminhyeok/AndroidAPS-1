package info.nightscout.androidaps.plugins.general.nsclient

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.R
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.database.entities.XXXValueWithUnit
import info.nightscout.androidaps.database.entities.UserEntry.Action
import info.nightscout.androidaps.database.entities.UserEntry.Sources
import info.nightscout.androidaps.database.entities.UserEntry.Units
import info.nightscout.androidaps.database.entities.UserEntry.ValueWithUnit
import info.nightscout.androidaps.database.transactions.SyncNsBolusTransaction
import info.nightscout.androidaps.database.transactions.SyncNsCarbsTransaction
import info.nightscout.androidaps.database.transactions.SyncNsTemporaryTargetTransaction
import info.nightscout.androidaps.database.transactions.SyncNsTherapyEventTransaction
import info.nightscout.androidaps.interfaces.ConfigInterface
import info.nightscout.androidaps.interfaces.DatabaseHelperInterface
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.logging.UserEntryLogger
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.receivers.DataWorker
import info.nightscout.androidaps.utils.JsonHelper
import info.nightscout.androidaps.utils.buildHelper.BuildHelper
import info.nightscout.androidaps.utils.extensions.bolusFromNsIdForInvalidating
import info.nightscout.androidaps.utils.extensions.carbsFromNsIdForInvalidating
import info.nightscout.androidaps.utils.extensions.temporaryTargetFromNsIdForInvalidating
import info.nightscout.androidaps.utils.extensions.therapyEventFromNsIdForInvalidating
import info.nightscout.androidaps.utils.sharedPreferences.SP
import java.util.concurrent.TimeUnit
import javax.inject.Inject

// This will not be needed fpr NS v3
// Now NS provides on _id of removed records

class NSClientRemoveWorker(
    context: Context,
    params: WorkerParameters) : Worker(context, params) {

    @Inject lateinit var nsClientPlugin: NSClientPlugin
    @Inject lateinit var dataWorker: DataWorker
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var buildHelper: BuildHelper
    @Inject lateinit var sp: SP
    @Inject lateinit var config: ConfigInterface
    @Inject lateinit var repository: AppRepository
    @Inject lateinit var databaseHelper: DatabaseHelperInterface
    @Inject lateinit var rxBus: RxBusWrapper
    @Inject lateinit var uel: UserEntryLogger

    override fun doWork(): Result {
        val acceptNSData = !sp.getBoolean(R.string.key_ns_upload_only, true) && buildHelper.isEngineeringMode() || config.NSCLIENT
        if (!acceptNSData) return Result.failure()

        var ret = Result.success()

        val treatments = dataWorker.pickupJSONArray(inputData.getLong(DataWorker.STORE_KEY, -1))
            ?: return Result.failure()

        for (i in 0 until treatments.length()) {
            val json = treatments.getJSONObject(i)
            val nsId = JsonHelper.safeGetString(json, "_id") ?: continue

            // room  Temporary target
            val temporaryTarget = temporaryTargetFromNsIdForInvalidating(nsId)
            repository.runTransactionForResult(SyncNsTemporaryTargetTransaction(temporaryTarget))
                .doOnError {
                    aapsLogger.error(LTag.DATABASE, "Error while invalidating temporary target", it)
                    ret = Result.failure()
                }
                .blockingGet()
                .also { result ->
                    /*result.invalidated.forEach { tt ->
                        uel.log(
                            UserEntry.Action.TT_DELETED_FROM_NS,
                            XXXValueWithUnit.TherapyEventTTReason(tt.reason),
                            XXXValueWithUnit.Mgdl(tt.lowTarget),
                            XXXValueWithUnit.Mgdl(tt.highTarget).takeIf { tt.lowTarget != tt.highTarget },
                            XXXValueWithUnit.Minute(tt.duration.toInt() / 60000).takeIf { tt.duration != 0L }
                        )
                    }
                    */
                    result.invalidated.forEach {
                        uel.log(Action.TT_REMOVED,
                            ValueWithUnit(Sources.NSClient),
                            ValueWithUnit(it.reason.text, Units.TherapyEvent),
                            ValueWithUnit(it.lowTarget, Units.Mg_Dl, true),
                            ValueWithUnit(it.highTarget, Units.Mg_Dl, it.lowTarget != it.highTarget),
                            ValueWithUnit(TimeUnit.MILLISECONDS.toMinutes(it.duration).toInt(), Units.M, it.duration != 0L)
                        )
                    }
                }

            // room  Therapy Event
            val therapyEvent = therapyEventFromNsIdForInvalidating(nsId)
            repository.runTransactionForResult(SyncNsTherapyEventTransaction(therapyEvent))
                .doOnError {
                    aapsLogger.error(LTag.DATABASE, "Error while invalidating therapy event", it)
                    ret = Result.failure()
                }
                .blockingGet()
                .also { result ->
                    result.invalidated.forEach {
                        /*uel.log(
                            UserEntry.Action.CAREPORTAL_DELETED_FROM_NS, (it.note ?: ""),
                            XXXValueWithUnit.Timestamp(it.timestamp),
                            XXXValueWithUnit.TherapyEventType(it.type))
                        */
                        uel.log(Action.CAREPORTAL_REMOVED, (it.note ?: ""),
                            ValueWithUnit(Sources.NSClient),
                            ValueWithUnit(it.timestamp, Units.Timestamp, true),
                            ValueWithUnit(it.type.text, Units.TherapyEvent))

                    }
                }

            // room  Bolus
            val bolus = bolusFromNsIdForInvalidating(nsId)
            repository.runTransactionForResult(SyncNsBolusTransaction(bolus))
                .doOnError {
                    aapsLogger.error(LTag.DATABASE, "Error while invalidating bolus", it)
                    ret = Result.failure()
                }
                .blockingGet()
                .also { result ->
                    result.invalidated.forEach {
                        uel.log(Action.CAREPORTAL_REMOVED,
                            ValueWithUnit(Sources.NSClient),
                            ValueWithUnit(it.timestamp, Units.Timestamp, true),
                            ValueWithUnit(it.amount, Units.U))
                    }
                }

            // room  Bolus
            val carbs = carbsFromNsIdForInvalidating(nsId)
            repository.runTransactionForResult(SyncNsCarbsTransaction(carbs))
                .doOnError {
                    aapsLogger.error(LTag.DATABASE, "Error while invalidating carbs", it)
                    ret = Result.failure()
                }
                .blockingGet()
                .also { result ->
                    result.invalidated.forEach {
                        uel.log(Action.CAREPORTAL_REMOVED,
                            ValueWithUnit(Sources.NSClient),
                            ValueWithUnit(it.timestamp, Units.Timestamp, true),
                            ValueWithUnit(it.amount, Units.G))
                    }
                }

            // old DB model
            databaseHelper.deleteTempBasalById(nsId)
            databaseHelper.deleteExtendedBolusById(nsId)
            databaseHelper.deleteProfileSwitchById(nsId)
        }

        return ret
    }

    init {
        (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)
    }
}