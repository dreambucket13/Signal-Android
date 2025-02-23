package org.thoughtcrime.securesms.contacts.sync

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.contacts.SystemContactsRepository
import org.signal.core.util.Stopwatch
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.contacts.sync.FuzzyPhoneNumberHelper.InputResult
import org.thoughtcrime.securesms.contacts.sync.FuzzyPhoneNumberHelper.OutputResult
import org.thoughtcrime.securesms.database.RecipientDatabase.CdsV2Result
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.RetrieveProfileJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.whispersystems.signalservice.api.push.ACI
import org.whispersystems.signalservice.api.services.CdsiV2Service
import java.io.IOException
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.Future

/**
 * Performs the CDS refresh using the V2 interface (either CDSH or CDSI) that returns both PNIs and ACIs.
 */
object ContactDiscoveryRefreshV2 {

  // Using Log.tag will cut off the version number
  private const val TAG = "CdsRefreshV2"

  /**
   * The maximum number items we will allow in a 'one-off' request.
   * One-off requests, while much faster, will always deduct the request size from our rate limit.
   * So we need to be careful about making it too large.
   * If a request size is over this limit, we will always fall back to a full sync.
   */
  private const val MAXIMUM_ONE_OFF_REQUEST_SIZE = 3

  @Throws(IOException::class)
  @WorkerThread
  @Synchronized
  @JvmStatic
  fun refreshAll(context: Context, useCompat: Boolean, ignoreResults: Boolean = false): ContactDiscovery.RefreshResult {
    val recipientE164s: Set<String> = SignalDatabase.recipients.getAllE164s().sanitize()
    val systemE164s: Set<String> = SystemContactsRepository.getAllDisplayNumbers(context).toE164s(context).sanitize()

    return refreshInternal(
      recipientE164s = recipientE164s,
      systemE164s = systemE164s,
      inputPreviousE164s = SignalDatabase.cds.getAllE164s(),
      saveToken = true,
      useCompat = useCompat,
      ignoreResults = ignoreResults
    )
  }

  @Throws(IOException::class)
  @WorkerThread
  @Synchronized
  @JvmStatic
  fun refresh(context: Context, inputRecipients: List<Recipient>, useCompat: Boolean, ignoreResults: Boolean = false): ContactDiscovery.RefreshResult {
    val recipients: List<Recipient> = inputRecipients.map { it.resolve() }
    val inputE164s: Set<String> = recipients.mapNotNull { it.e164.orElse(null) }.toSet()

    return if (inputE164s.size > MAXIMUM_ONE_OFF_REQUEST_SIZE) {
      Log.i(TAG, "List of specific recipients to refresh is too large! (Size: ${recipients.size}). Doing a full refresh instead.")

      val fullResult: ContactDiscovery.RefreshResult = refreshAll(context, ignoreResults)
      val inputIds: Set<RecipientId> = recipients.map { it.id }.toSet()

      ContactDiscovery.RefreshResult(
        registeredIds = fullResult.registeredIds.intersect(inputIds),
        rewrites = fullResult.rewrites.filterKeys { inputE164s.contains(it) }
      )
    } else {
      refreshInternal(
        recipientE164s = inputE164s,
        systemE164s = inputE164s,
        inputPreviousE164s = emptySet(),
        saveToken = false,
        useCompat = useCompat,
        ignoreResults = ignoreResults
      )
    }
  }

  @Throws(IOException::class)
  private fun refreshInternal(
    recipientE164s: Set<String>,
    systemE164s: Set<String>,
    inputPreviousE164s: Set<String>,
    saveToken: Boolean,
    useCompat: Boolean,
    ignoreResults: Boolean
  ): ContactDiscovery.RefreshResult {
    val stopwatch = Stopwatch("refreshInternal-${if (useCompat) "compat" else "v2"}")

    val previousE164s: Set<String> = if (SignalStore.misc().cdsToken != null) inputPreviousE164s else emptySet()

    val allE164s: Set<String> = recipientE164s + systemE164s
    val newRawE164s: Set<String> = allE164s - previousE164s
    val fuzzyInput: InputResult = FuzzyPhoneNumberHelper.generateInput(newRawE164s, recipientE164s)
    val newE164s: Set<String> = fuzzyInput.numbers

    if (newE164s.isEmpty() && previousE164s.isEmpty()) {
      Log.w(TAG, "[refreshInternal] No data to send! Ignoring.")
      return ContactDiscovery.RefreshResult(emptySet(), emptyMap())
    }

    val token: ByteArray? = if (previousE164s.isNotEmpty()) SignalStore.misc().cdsToken else null

    stopwatch.split("preamble")

    val response: CdsiV2Service.Response = ApplicationDependencies.getSignalServiceAccountManager().getRegisteredUsersWithCdsi(
      previousE164s,
      newE164s,
      SignalDatabase.recipients.getAllServiceIdProfileKeyPairs(),
      useCompat,
      Optional.ofNullable(token),
      BuildConfig.CDSI_MRENCLAVE
    ) { tokenToSave ->
      if (saveToken) {
        SignalStore.misc().cdsToken = tokenToSave
        Log.d(TAG, "Token saved!")
      } else {
        Log.d(TAG, "Ignoring token.")
      }
    }
    Log.d(TAG, "[refreshInternal] Used ${response.quotaUsedDebugOnly} quota.")
    stopwatch.split("network")

    SignalDatabase.cds.updateAfterCdsQuery(newE164s, allE164s + newE164s)
    stopwatch.split("cds-db")

    val registeredIds: MutableSet<RecipientId> = mutableSetOf()
    val rewrites: MutableMap<String, String> = mutableMapOf()

    if (ignoreResults) {
      Log.w(TAG, "[refreshInternal] Ignoring CDSv2 results.")
    } else {
      if (useCompat) {
        val transformed: Map<String, ACI?> = response.results.mapValues { entry -> entry.value.aci.orElse(null) }
        val fuzzyOutput: OutputResult<ACI> = FuzzyPhoneNumberHelper.generateOutput(transformed, fuzzyInput)

        if (transformed.values.any { it == null }) {
          throw IOException("Unexpected null ACI!")
        }

        SignalDatabase.recipients.rewritePhoneNumbers(fuzzyOutput.rewrites)
        stopwatch.split("rewrite-e164")

        val aciMap: Map<RecipientId, ACI?> = SignalDatabase.recipients.bulkProcessCdsResult(fuzzyOutput.numbers)

        registeredIds += aciMap.keys
        rewrites += fuzzyOutput.rewrites
        stopwatch.split("process-result")

        val existingIds: Set<RecipientId> = SignalDatabase.recipients.getAllPossiblyRegisteredByE164(recipientE164s + rewrites.values)
        val inactiveIds: Set<RecipientId> = (existingIds - registeredIds).removeRegisteredButUnlisted()

        SignalDatabase.recipients.bulkUpdatedRegisteredStatus(aciMap, inactiveIds)
        stopwatch.split("update-registered")
      } else {
        val transformed: Map<String, CdsV2Result> = response.results.mapValues { entry -> CdsV2Result(entry.value.pni, entry.value.aci.orElse(null)) }
        val fuzzyOutput: OutputResult<CdsV2Result> = FuzzyPhoneNumberHelper.generateOutput(transformed, fuzzyInput)

        SignalDatabase.recipients.rewritePhoneNumbers(fuzzyOutput.rewrites)
        stopwatch.split("rewrite-e164")

        val existingIds: Set<RecipientId> = SignalDatabase.recipients.getAllPossiblyRegisteredByE164(recipientE164s + rewrites.values)
        val inactiveIds: Set<RecipientId> = (existingIds - registeredIds).removeRegisteredButUnlisted()

        registeredIds += SignalDatabase.recipients.bulkProcessCdsV2Result(fuzzyOutput.numbers)
        rewrites += fuzzyOutput.rewrites
        stopwatch.split("process-result")

        SignalDatabase.recipients.bulkUpdatedRegisteredStatusV2(registeredIds, inactiveIds)
        stopwatch.split("update-registered")
      }
    }

    stopwatch.stop(TAG)

    return ContactDiscovery.RefreshResult(registeredIds, rewrites)
  }

  private fun hasCommunicatedWith(recipient: Recipient): Boolean {
    val localAci = SignalStore.account().requireAci()
    return SignalDatabase.threads.hasThread(recipient.id) || (recipient.hasServiceId() && SignalDatabase.sessions.hasSessionFor(localAci, recipient.requireServiceId().toString()))
  }

  @WorkerThread
  private fun Set<RecipientId>.removeRegisteredButUnlisted(): Set<RecipientId> {
    val futures: List<Future<Pair<RecipientId, Boolean?>>> = Recipient.resolvedList(this)
      .filter { hasCommunicatedWith(it) }
      .map {
        SignalExecutors.UNBOUNDED.submit(
          Callable {
            try {
              it.id to ApplicationDependencies.getSignalServiceAccountManager().isIdentifierRegistered(it.requireServiceId())
            } catch (e: IOException) {
              it.id to null
            }
          }
        )
      }

    val registeredIds: MutableSet<RecipientId> = mutableSetOf()
    val retryIds: MutableSet<RecipientId> = mutableSetOf()

    for (future in futures) {
      val (id, registered) = future.get()
      if (registered == null) {
        retryIds += id
        registeredIds += id
      } else if (registered) {
        registeredIds += id
      }
    }

    if (retryIds.isNotEmpty()) {
      Log.w(TAG, "Failed to determine registered status of ${retryIds.size} recipients. Assuming registered, but enqueuing profile jobs to check later.")
      RetrieveProfileJob.enqueue(retryIds)
    }

    return this - registeredIds
  }

  private fun Set<String>.toE164s(context: Context): Set<String> {
    return this.map { PhoneNumberFormatter.get(context).format(it) }.toSet()
  }

  private fun Set<String>.sanitize(): Set<String> {
    return this
      .filter {
        try {
          it.startsWith("+") && it.length > 1 && it[1] != '0' && it.toLong() > 0
        } catch (e: NumberFormatException) {
          false
        }
      }
      .toSet()
  }
}
