package org.totschnig.myexpenses.viewmodel

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import app.cash.copper.flow.observeQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.totschnig.myexpenses.model.Account
import org.totschnig.myexpenses.model.Grouping
import org.totschnig.myexpenses.provider.DatabaseConstants.*
import org.totschnig.myexpenses.provider.DbUtils
import org.totschnig.myexpenses.provider.TransactionProvider
import org.totschnig.myexpenses.viewmodel.data.Category2
import org.totschnig.myexpenses.viewmodel.data.DateInfo2
import org.totschnig.myexpenses.viewmodel.data.DateInfo3
import org.totschnig.myexpenses.viewmodel.data.DistributionAccountInfo
import timber.log.Timber
import java.util.*

open class DistributionViewModel(application: Application, savedStateHandle: SavedStateHandle) :
    CategoryViewModel(application, savedStateHandle) {
    val selectionState: MutableState<Category2?> = mutableStateOf(null)
    val expansionState: SnapshotStateList<Category2> = SnapshotStateList()
    private val accountInfo = MutableStateFlow<DistributionAccountInfo?>(null)

    private val _aggregateTypes = MutableStateFlow(true)
    private val _incomeType = MutableStateFlow(false)
    private val _grouping = MutableStateFlow(GroupingInfo(Grouping.NONE))

    val aggregateTypes: Boolean
        get() = _aggregateTypes.value

    val incomeType: Boolean
        get() = _incomeType.value

    val grouping: Grouping
        get() = _grouping.value.grouping

    fun setAggregateTypes(newValue: Boolean) {
        _aggregateTypes.tryEmit(newValue)
    }

    fun setIncomeType(newValue: Boolean) {
        _incomeType.tryEmit(newValue)
    }

    fun setGrouping(grouping: Grouping) {
        viewModelScope.launch {
            dateInfo.filterNotNull().collect {
                _grouping.tryEmit(
                    GroupingInfo(
                        grouping = grouping,
                        year = when (grouping) {
                            Grouping.WEEK -> it.yearOfWeekStart
                            Grouping.MONTH -> it.yearOfMonthStart
                            else -> it.year
                        },
                        second = when (grouping) {
                            Grouping.DAY -> it.day
                            Grouping.WEEK -> it.week
                            Grouping.MONTH -> it.month
                            else -> 0
                        }
                    )
                )
            }
        }
    }

    fun forward() {
        if (grouping == Grouping.YEAR) {
            _grouping.tryEmit(
                _grouping.value.copy(
                    year = _grouping.value.year + 1
                )
            )
        } else {
            viewModelScope.launch {
                dateInfoExtra.filterNotNull().collect {
                    val nextSecond = _grouping.value.second + 1
                    val currentYear = _grouping.value.year
                    val overflow = nextSecond > it.maxValue
                    _grouping.tryEmit(
                        _grouping.value.copy(
                            year = if (overflow) currentYear + 1 else currentYear,
                            second = if (overflow) grouping.minValue else nextSecond
                        )
                    )
                }
            }
        }
    }

    fun backward() {
        if (grouping == Grouping.YEAR) {
            _grouping.tryEmit(
                _grouping.value.copy(
                    year = _grouping.value.year - 1
                )
            )
        } else {
            viewModelScope.launch {
                dateInfoExtra.filterNotNull().take(1).collect {
                    val nextSecond = _grouping.value.second - 1
                    val currentYear = _grouping.value.year
                    val underflow = nextSecond < grouping.minValue
                    _grouping.tryEmit(
                        _grouping.value.copy(
                            year = if (underflow) currentYear - 1 else currentYear,
                            second = if (underflow) it.maxValue else nextSecond
                        )
                    )
                }
            }
        }
    }

    fun initWithAccount(accountId: Long) {
        viewModelScope.launch {
            account(accountId, true).asFlow().collect {
                accountInfo.tryEmit(
                    DistributionAccountInfo(
                        it.id,
                        it.getLabelForScreenTitle(getApplication()),
                        it.currencyUnit,
                        it.color
                    )
                )
            }
        }
    }

    private val dateInfo: StateFlow<DateInfo2?> = contentResolver.observeQuery(
        uri = TransactionProvider.DUAL_URI,
        projection = arrayOf(
            "${getThisYearOfWeekStart()} AS $KEY_THIS_YEAR_OF_WEEK_START",
            "${getThisYearOfMonthStart()} AS $KEY_THIS_YEAR_OF_MONTH_START",
            "$THIS_YEAR AS $KEY_THIS_YEAR",
            "${getThisMonth()} AS $KEY_THIS_MONTH",
            "${getThisWeek()} AS $KEY_THIS_WEEK",
            "$THIS_DAY AS $KEY_THIS_DAY"
        ),
        selection = null, selectionArgs = null, sortOrder = null, notifyForDescendants = false
    ).transform { query ->
        withContext(Dispatchers.IO) {
            query.run()?.use { cursor ->
                cursor.moveToFirst()
                DateInfo2.fromCursor(cursor)
            }
        }?.let {
            emit(it)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val dateInfoExtra: StateFlow<DateInfo3?> = _grouping.flatMapLatest { grouping ->
        Timber.d("emitting $grouping")
        //if we are at the beginning of the year we are interested in the max of the previous year
        val maxYearToLookUp = if (grouping.second <= 1) grouping.year - 1 else grouping.year
        val maxValueExpression = when (grouping.grouping) {
            Grouping.DAY -> String.format(
                Locale.US,
                "strftime('%%j','%d-12-31')",
                maxYearToLookUp
            )
            Grouping.WEEK -> DbUtils.maximumWeekExpression(maxYearToLookUp)
            Grouping.MONTH -> "11"
            else -> "0"
        }
        val projectionList = buildList {
            add("$maxValueExpression AS $KEY_MAX_VALUE")
            if (grouping.grouping == Grouping.WEEK) {
                //we want to find out the week range when we are given a week number
                //we find out the first day in the year, which is the beginning of week "0" and then
                //add (weekNumber)*7 days to get at the beginning of the week
                add(
                    DbUtils.weekStartFromGroupSqlExpression(
                        grouping.year,
                        grouping.second
                    )
                )
                add(
                    DbUtils.weekEndFromGroupSqlExpression(
                        grouping.year,
                        grouping.second
                    )
                )
            }
        }
        contentResolver.observeQuery(
            uri = TransactionProvider.DUAL_URI,
            projection = projectionList.toTypedArray(),
            selection = null, selectionArgs = null, sortOrder = null, notifyForDescendants = false
        ).transform { query ->
            withContext(Dispatchers.IO) {
                query.run()?.use { cursor ->
                    cursor.moveToFirst()
                    DateInfo3.fromCursor(cursor)
                }
            }?.let {
                emit(it)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val categoryTreeWithSum: StateFlow<Category2> = combine(
        accountInfo.filterNotNull(),
        _aggregateTypes,
        _incomeType,
        _grouping
    ) { accountInfo, aggregateTypes, incomeType, grouping ->
        Triple(accountInfo, if (aggregateTypes) null else incomeType, grouping)
    }.flatMapLatest { (accountInfo, incomeType, grouping) ->
        categoryTree(
            null,
            null,
            arrayOf("*", sumColumn(accountInfo, incomeType, grouping)),
            true
        ) { it.sum != 0L }
    }.stateIn(viewModelScope, SharingStarted.Lazily, Category2.EMPTY)

    private fun sumColumn(
        accountInfo: DistributionAccountInfo,
        incomeType: Boolean?,
        grouping: GroupingInfo
    ): String {
        val accountSelection: String?
        var amountCalculation = KEY_AMOUNT
        var table = VIEW_COMMITTED
        when {
            accountInfo.id == Account.HOME_AGGREGATE_ID -> {
                accountSelection = null
                amountCalculation =
                    getAmountHomeEquivalent(VIEW_WITH_ACCOUNT)
                table = VIEW_WITH_ACCOUNT
            }
            accountInfo.id < 0 -> {
                accountSelection =
                    " IN (SELECT $KEY_ROWID from $TABLE_ACCOUNTS WHERE $KEY_CURRENCY = '${accountInfo.currency.code}' AND $KEY_EXCLUDE_FROM_TOTALS = 0 )"
            }
            else -> {
                accountSelection = " = ${accountInfo.id}"
            }
        }
        var catFilter =
            "FROM $table WHERE ${WHERE_NOT_VOID}${if (accountSelection == null) "" else " AND +${KEY_ACCOUNTID}$accountSelection"} AND $KEY_CATID = Tree.${KEY_ROWID}"
        if (incomeType != null) {
            catFilter += " AND " + KEY_AMOUNT + (if (incomeType) ">" else "<") + "0"
        }
        val dateFilter = buildFilterClause(grouping)
        if (dateFilter != null) {
            catFilter += " AND $dateFilter"
        }
        //val extraColumn = extraColumn
        return "(SELECT sum($amountCalculation) $catFilter) AS $KEY_SUM"
/*        if (extraColumn != null) {
            projection.add(extraColumn)
        }*/
    }

    private fun buildFilterClause(groupingInfo: GroupingInfo) = with(groupingInfo) {
        val yearExpression = "$YEAR = ${getThisYearOfMonthStart()}"
        when (grouping) {
            Grouping.YEAR -> yearExpression
            Grouping.DAY -> "$yearExpression AND $DAY = $second"
            Grouping.WEEK -> "${getYearOfWeekStart()} = $year AND ${getWeek()} = $second"
            Grouping.MONTH -> "${getYearOfMonthStart()} = $year AND ${getMonth()} = $second"
            else -> null
        }
    }

    data class GroupingInfo(
        val grouping: Grouping,
        val year: Int = -1,
        val second: Int = -1
    )
}