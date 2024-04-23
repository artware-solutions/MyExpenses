package org.totschnig.myexpenses.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.charts.RadarChart
import com.github.mikephil.charting.data.RadarData
import com.github.mikephil.charting.data.RadarDataSet
import com.github.mikephil.charting.data.RadarEntry
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.google.android.material.chip.ChipGroup
import eltos.simpledialogfragment.SimpleDialog
import eltos.simpledialogfragment.SimpleDialog.OnDialogResultListener
import eltos.simpledialogfragment.form.AmountInputHostDialog
import eltos.simpledialogfragment.form.Check
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.compose.AppTheme
import org.totschnig.myexpenses.compose.Budget
import org.totschnig.myexpenses.compose.ExpansionMode
import org.totschnig.myexpenses.compose.TEST_TAG_BUDGET_ROOT
import org.totschnig.myexpenses.compose.breakPoint
import org.totschnig.myexpenses.compose.rememberMutableStateListOf
import org.totschnig.myexpenses.injector
import org.totschnig.myexpenses.model.ContribFeature
import org.totschnig.myexpenses.model.CurrencyUnit
import org.totschnig.myexpenses.model.Grouping
import org.totschnig.myexpenses.model.Money
import org.totschnig.myexpenses.model.Sort
import org.totschnig.myexpenses.preference.PrefKey
import org.totschnig.myexpenses.provider.DatabaseConstants
import org.totschnig.myexpenses.util.TextUtils.concatResStrings
import org.totschnig.myexpenses.util.buildAmountField
import org.totschnig.myexpenses.util.setEnabledAndVisible
import org.totschnig.myexpenses.util.ui.addChipsBulk
import org.totschnig.myexpenses.viewmodel.BudgetViewModel2
import org.totschnig.myexpenses.viewmodel.data.Budget
import org.totschnig.myexpenses.viewmodel.data.Category
import timber.log.Timber
import java.math.BigDecimal
import kotlin.math.pow


class BudgetActivity : DistributionBaseActivity<BudgetViewModel2>(), OnDialogResultListener {
    companion object {
        private const val EDIT_BUDGET_DIALOG = "EDIT_BUDGET"
        private const val DELETE_BUDGET_DIALOG = "DELETE_BUDGET"
        private const val DELETE_ROLLOVER_DIALOG = "DELETE_ROLLOVER"
    }

    override val viewModel: BudgetViewModel2 by viewModels()
    private lateinit var sortDelegate: SortDelegate
    private var hasRollovers: Boolean? = null
    private lateinit var chart: RadarChart

    override val showChartPrefKey = PrefKey.BUDGEt_SHOW_CHART

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!licenceHandler.hasTrialAccessTo(ContribFeature.BUDGET)) {
            contribFeatureRequested(ContribFeature.BUDGET)
            finish()
            return
        }
        val binding = setupView()
        injector.inject(viewModel)
        sortDelegate = SortDelegate(
            defaultSortOrder = Sort.ALLOCATED,
            prefKey = PrefKey.SORT_ORDER_BUDGET_CATEGORIES,
            options = arrayOf(Sort.LABEL, Sort.ALLOCATED, Sort.SPENT),
            prefHandler = prefHandler,
            collate = collate
        )
        viewModel.setSortOrder(sortDelegate.currentSortOrder)
        val budgetId: Long = intent.getLongExtra(DatabaseConstants.KEY_ROWID, 0)
        val groupingYear = intent.getIntExtra(DatabaseConstants.KEY_YEAR, 0)
        val groupingSecond = intent.getIntExtra(DatabaseConstants.KEY_SECOND_GROUP, 0)
        viewModel.initWithBudget(budgetId, groupingYear, groupingSecond)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.accountInfo.filterNotNull().collect {
                    supportActionBar?.title = it.title
                }
            }
        }
        binding.composeView.setContent {
            AppTheme {
                val category =
                    viewModel.categoryTreeForBudget.collectAsState(initial = Category.LOADING).value
                val budget = viewModel.accountInfo.collectAsState(null).value
                Box(modifier = Modifier.fillMaxSize()) {
                    if (category === Category.LOADING || budget == null) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(96.dp)
                                .align(Alignment.Center)
                        )
                    } else {
                        LaunchedEffect(category) {
                            setChartData(category, budget.currencyUnit.fractionDigits)
                        }
                        hasRollovers = category.hasRolloverNext
                        Column(verticalArrangement = Arrangement.Center) {
                            RenderFilters(budget)
                            LayoutHelper(
                                data = {
                                    RenderBudget(it, category, budget)
                                }, chart = {
                                    RenderChart(it, category, budget.currencyUnit.fractionDigits)
                                })
                            val editRollOverInValid = viewModel.editRollOverInValid
                            LaunchedEffect(editRollOverInValid) {
                                invalidateOptionsMenu()
                            }
                            if (editRollOverInValid) {
                                Snackbar {
                                    Text(text = stringResource(R.string.rollover_edit_invalid))
                                }
                            }
                        }
                    }
                }
            }
        }
        viewModel.setAllocatedOnly(
            prefHandler.getBoolean(
                templateForAllocatedOnlyKey(budgetId),
                false
            )
        )
    }

    private fun requireChart(context: Context) = RadarChart(context).also { chart = it }

    @Composable
    fun RenderFilters(budget: Budget) {
        val whereFilter = viewModel.whereFilter.collectAsState().value
        AndroidView(
            modifier = Modifier.padding(horizontal = dimensionResource(id = eltos.simpledialogfragment.R.dimen.activity_horizontal_margin)),
            factory = { ChipGroup(it) },
            update = { chipGroup ->
                chipGroup.addChipsBulk(buildList {
                    add(budget.label(this@BudgetActivity))
                    whereFilter.criteria.forEach {
                        add(it.prettyPrint(this@BudgetActivity))
                    }
                })
            }
        )
    }

    @Composable
    fun RenderBudget(
        modifier: Modifier,
        category: Category,
        budget: Budget
    ) {
        val sort = viewModel.sortOrder.collectAsState()
        BoxWithConstraints(modifier = modifier.testTag(TEST_TAG_BUDGET_ROOT)) {
            val narrowScreen = maxWidth.value < breakPoint.value
            Timber.d("narrowScreen : %b (%f)", narrowScreen, maxWidth.value)

            Budget(
                category = category.copy(
                    sum = viewModel.sum.collectAsState().value,
                ).let {
                    when (sort.value) {
                        Sort.SPENT -> it.sortChildrenBySumRecursive()
                        Sort.ALLOCATED -> it.sortChildrenByBudgetRecursive()
                        else -> it
                    }
                },
                expansionMode = ExpansionMode.DefaultCollapsed(
                    rememberMutableStateListOf()
                ),
                currency = budget.currencyUnit,
                onBudgetEdit = { cat, parent ->
                    showEditBudgetDialog(
                        cat,
                        parent,
                        budget.currencyUnit,
                        budget.grouping != Grouping.NONE
                    )
                },
                onShowTransactions = {
                    lifecycleScope.launch {
                        showTransactions(it)
                    }
                },
                hasRolloverNext = category.hasRolloverNext,
                editRollOver = if (viewModel.duringRollOverEdit) {
                    viewModel.editRollOverMap
                } else null,
                narrowScreen = narrowScreen,
                showChart = showChart.value
            )
        }
    }

    @Composable
    fun RenderChart(modifier: Modifier, category: Category, fractionDigits: Int) {
        if (category.children.count { it.budget.totalAllocated > 0 } >= 3)
            AndroidView(
                modifier = modifier,
                factory = { ctx ->
                    requireChart(ctx).apply {

                        description.isEnabled = false

                        webLineWidth = 1f
                        webColor = Color.LTGRAY
                        webLineWidthInner = 1f
                        webColorInner = Color.LTGRAY

                        animateXY(1400, 1400, Easing.EaseInOutQuad)

                        with(xAxis) {
                            //typeface = tfLight
                            setTextSize(9f)
                            yOffset = 0f
                            xOffset = 0f
                            textColor = textColorSecondary.defaultColor
                        }


                        with(yAxis) {
                            //typeface = tfLight
                            setLabelCount(5, false)
                            setTextSize(9f)
                            textColor = textColorSecondary.defaultColor
                        }
                        legend.isEnabled = false
                        setChartData(category, fractionDigits)
                    }
                }
            )
        else
            Text(
                "Not enough data",
                modifier.wrapContentHeight(),
                textAlign = TextAlign.Center
            )
    }

    private fun showEditBudgetDialog(
        category: Category,
        parentItem: Category?,
        currencyUnit: CurrencyUnit,
        withOneTimeCheck: Boolean
    ) {
        val simpleFormDialog = AmountInputHostDialog.build()
            .title(if (category.level > 0) category.label else getString(R.string.dialog_title_edit_budget))
            .neg()
        val amount = Money(currencyUnit, category.budget.budget)
        //The rollOver reduces the amount we need to allocate specific for this period
        val min =
            category.children.sumOf { it.budget.totalAllocated } - category.budget.rollOverPrevious
        val max = if (category.level > 0) {
            val bundle = Bundle(1).apply {
                putLong(DatabaseConstants.KEY_CATID, category.id)
            }
            simpleFormDialog.extra(bundle)
            val allocated: Long = parentItem?.children?.sumOf { it.budget.totalAllocated }
                ?: category.budget.totalAllocated
            val allocatable = parentItem?.budget?.totalAllocated?.minus(allocated)
            val maxLong = allocatable?.plus(category.budget.totalAllocated)
            if (maxLong != null && maxLong <= 0) {
                showSnackBar(
                    concatResStrings(
                        this, " ",
                        if (category.level == 1) R.string.budget_exceeded_error_1_2 else R.string.sub_budget_exceeded_error_1_2,
                        if (category.level == 1) R.string.budget_exceeded_error_2 else R.string.sub_budget_exceeded_error_2
                    )
                )
                return
            }
            maxLong
        } else null
        simpleFormDialog
            .fields(
                *buildList {
                    add(
                        buildAmountField(
                            amount,
                            max?.let { Money(currencyUnit, it).amountMajor },
                            Money(currencyUnit, min).amountMajor,
                            category.level,
                            this@BudgetActivity
                        )
                    )
                    if (withOneTimeCheck)
                        add(
                            Check.box(DatabaseConstants.KEY_ONE_TIME)
                                .label(
                                    getString(
                                        R.string.budget_only_current_period,
                                        supportActionBar?.subtitle
                                    )
                                )
                                .check(category.budget.oneTime)
                        )
                }.toTypedArray()
            )
            .show(this, EDIT_BUDGET_DIALOG)
    }

    override fun onResult(dialogTag: String, which: Int, extras: Bundle): Boolean {
        if (which == OnDialogResultListener.BUTTON_POSITIVE) {
            val budget = viewModel.accountInfo.value ?: return false
            when (dialogTag) {
                EDIT_BUDGET_DIALOG -> {
                    val amount = Money(
                        budget.currencyUnit,
                        (extras.getSerializable(DatabaseConstants.KEY_AMOUNT) as BigDecimal)
                    )
                    viewModel.updateBudget(
                        budget.id,
                        extras.getLong(DatabaseConstants.KEY_CATID),
                        amount,
                        extras.getBoolean(DatabaseConstants.KEY_ONE_TIME)
                    )
                    return true
                }

                DELETE_BUDGET_DIALOG -> {
                    viewModel.deleteBudget(
                        budgetId = budget.id
                    ).observe(this) {
                        if (it) {
                            setResult(Activity.RESULT_FIRST_USER)
                            finish()
                        } else {
                            showDeleteFailureFeedback()
                        }
                    }
                    return true
                }

                DELETE_ROLLOVER_DIALOG -> {
                    viewModel.rollOverClear()
                    return true
                }
            }
        }
        return false
    }

    override fun dispatchCommand(command: Int, tag: Any?) =
        if (super.dispatchCommand(command, tag)) {
            true
        } else when (command) {
            R.id.MANAGE_CATEGORIES_COMMAND -> {
                startActivity(Intent(this, ManageCategories::class.java).apply {
                    action = Action.MANAGE.name
                })
                true
            }

            R.id.BUDGET_ALLOCATED_ONLY -> {
                viewModel.accountInfo.value?.let {
                    val value = tag as Boolean
                    viewModel.setAllocatedOnly(value)
                    prefHandler.putBoolean(templateForAllocatedOnlyKey(it.id), value)
                    invalidateOptionsMenu()
                    reset()
                }
                true
            }

            R.id.EDIT_COMMAND -> {
                viewModel.accountInfo.value?.let {
                    startActivity(Intent(this, BudgetEdit::class.java).apply {
                        putExtra(DatabaseConstants.KEY_ROWID, it.id)
                    })
                }
                true
            }

            R.id.DELETE_COMMAND -> {
                viewModel.accountInfo.value?.let {
                    SimpleDialog.build()
                        .title(R.string.dialog_title_warning_delete_budget)
                        .msg(
                            getString(
                                R.string.warning_delete_budget,
                                it.title
                            ) + " " + getString(R.string.continue_confirmation)
                        )
                        .pos(R.string.menu_delete)
                        .neg(android.R.string.cancel)
                        .show(this, DELETE_BUDGET_DIALOG)
                }
                true
            }

            R.id.ROLLOVER_TOTAL -> {
                viewModel.rollOverTotal()
                true
            }

            R.id.ROLLOVER_CLEAR -> {
                SimpleDialog.build()
                    .title(supportActionBar?.subtitle?.toString())
                    .msg(
                        getString(R.string.dialog_confirm_rollover_delete) + " " +
                                getString(R.string.continue_confirmation)
                    )
                    .pos(R.string.menu_delete)
                    .neg(android.R.string.cancel)
                    .show(this, DELETE_ROLLOVER_DIALOG)
                true
            }

            R.id.ROLLOVER_CATEGORIES -> {
                viewModel.rollOverCategories()
                true
            }

            R.id.ROLLOVER_EDIT -> {
                if (viewModel.startRollOverEdit()) {
                    invalidateOptionsMenu()
                } else {
                    Toast.makeText(
                        this,
                        "RollOver Save still ongoing. Try again later",
                        Toast.LENGTH_LONG
                    ).show()
                }
                true
            }

            R.id.ROLLOVER_EDIT_CANCEL -> {
                viewModel.stopRollOverEdit()
                invalidateOptionsMenu()
                true
            }

            R.id.ROLLOVER_EDIT_SAVE -> {
                viewModel.stopRollOverEdit()
                invalidateOptionsMenu()
                viewModel.rollOverSave()
                true
            }

            else -> false
        }

    private fun templateForAllocatedOnlyKey(budgetId: Long) = "allocatedOnly_$budgetId"


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (viewModel.duringRollOverEdit) {
            menuInflater.inflate(R.menu.budget_rollover_edit, menu)
        } else {
            menuInflater.inflate(R.menu.budget, menu)
            super.onCreateOptionsMenu(menu)
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (viewModel.duringRollOverEdit) {
            menu.findItem(R.id.ROLLOVER_EDIT_SAVE).isEnabled = !viewModel.editRollOverInValid
        } else {
            sortDelegate.onPrepareOptionsMenu(menu)
            super.onPrepareOptionsMenu(menu)
            menu.findItem(R.id.BUDGET_ALLOCATED_ONLY)?.let {
                it.isChecked = viewModel.allocatedOnly
            }
            val grouped = viewModel.grouping != Grouping.NONE
            menu.findItem(R.id.ROLLOVER_COMMAND).setEnabledAndVisible(grouped)
            if (grouped) {
                menu.findItem(R.id.ROLLOVER_TOTAL).setEnabledAndVisible(hasRollovers == false)
                menu.findItem(R.id.ROLLOVER_CATEGORIES).setEnabledAndVisible(hasRollovers == false)
                menu.findItem(R.id.ROLLOVER_CLEAR).setEnabledAndVisible(hasRollovers == true)
            }
            lifecycleScope.launch {
                menu.findItem(R.id.AGGREGATE_COMMAND).isChecked = viewModel.aggregateNeutral.first()
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        if (sortDelegate.onOptionsItemSelected(item)) {
            invalidateOptionsMenu()
            viewModel.setSortOrder(sortDelegate.currentSortOrder)
            true
        } else super.onOptionsItemSelected(item)

    private fun setChartData(category: Category, fractionDigits: Int) {
        if ((::chart.isInitialized)) {
            val factor = 10.0.pow(fractionDigits).toFloat()
            val labels = mutableListOf<String>()
            val allocated = mutableListOf<RadarEntry>()
            val spent = mutableListOf<RadarEntry>()
            val categories = category.children.filter {
                it.budget.totalAllocated > 0
            }
            if (categories.size < 3) return
            categories.forEach {
                labels.add(it.label)
                allocated.add(RadarEntry(it.budget.totalAllocated / factor))
                spent.add(RadarEntry(-it.aggregateSum.toFloat() / factor))
            }
            with(chart) {
                xAxis.valueFormatter = IAxisValueFormatter { value, _ ->
                    labels[value.toInt() % labels.size]
                }
                setData(RadarData(buildList<RadarDataSet> {
                    add(
                        RadarDataSet(
                            allocated,
                            getString(R.string.budget_table_header_allocated)
                        ).apply {
                            setColor(ResourcesCompat.getColor(resources, R.color.colorIncome, null))
                            setLineWidth(2f)
                            isDrawHighlightCircleEnabled = true
                            setDrawHighlightIndicators(false)
                        })
                    add(RadarDataSet(spent, getString(R.string.budget_table_header_spent)).apply {
                        setColor(ResourcesCompat.getColor(resources, R.color.colorExpense, null))
                        setLineWidth(2f)
                        isDrawHighlightCircleEnabled = true
                        setDrawHighlightIndicators(false)
                    })
                }).apply {
                    setValueTextSize(8f)
                    setDrawValues(false)
                    setValueTextColor(Color.WHITE)
                })
                invalidate()
            }
        }
    }
}

/*
@Preview(uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun RadarChart() {
    Mdc3Theme {
        Surface(modifier = Modifier.size(200.dp)) {
            RenderChart(modifier = Modifier)
        }
    }
}
*/
