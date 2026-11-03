package io.nekohasekai.sagernet.ui.profiles

import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.banshan.BanShanSession
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.GroupSortMethod
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.TestBy
import io.nekohasekai.sagernet.database.TestShowItems
import io.nekohasekai.sagernet.database.TrafficBy
import io.nekohasekai.sagernet.database.TypeBy
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.test.TestSessionClient

/** The ⋮ menu of the profiles screen: actions on the current tab's group (the desktop's Groups menu). */
class GroupMenu(private val host: ConfigurationFragment) {

    /** Group actions that rewrite the profiles or the group row; a login-managed group refuses them. */
    private val managedActions = setOf(
        R.id.action_resolve_domains,
        R.id.action_remove_duplicates, R.id.action_remove_unavailable,
        R.id.action_remove_invalid, R.id.action_remove_insecure,
        R.id.action_sort_ascending, R.id.action_sort_descending,
        R.id.action_sort_manual, R.id.action_sort_type, R.id.action_sort_security,
        R.id.action_sort_address, R.id.action_sort_name, R.id.action_sort_test_result,
        R.id.action_sort_test_latency, R.id.action_sort_test_download, R.id.action_sort_test_upload,
        R.id.action_sort_test_ip_out, R.id.action_sort_traffic, R.id.action_sort_traffic_total,
        R.id.action_sort_traffic_downloaded, R.id.action_sort_traffic_uploaded,
    )

    /** Direction of the next sort; a group that keeps a sort shows its own direction. */
    var sortDescending = false

    fun setUp(menu: Menu) {
        menu.findItem(R.id.action_misc)?.subMenu?.let { MenuCompat.setGroupDividerEnabled(it, true) }
        keepOpen(menu, R.id.action_sort_ascending) { setDirection(it, false) }
        keepOpen(menu, R.id.action_sort_descending) { setDirection(it, true) }
        keepOpen(menu, R.id.action_show_out_ip) { toggleShown(menu, it) }
        keepOpen(menu, R.id.action_show_speed) { toggleShown(menu, it) }
    }

    /**
     * A toggle that leaves its popup open: MenuBuilder.performItemAction closes the menu after a click unless the
     * item has a collapsible action view whose expansion is refused.
     */
    private fun keepOpen(menu: Menu, id: Int, onClick: (MenuItem) -> Unit) {
        val item = menu.findItem(id) ?: return
        item.actionView = View(host.requireContext())
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = false
            override fun onMenuItemActionCollapse(item: MenuItem) = true
        })
        item.setOnMenuItemClickListener {
            onClick(it)
            false
        }
    }

    /** Like a click on the sorted column's header: a kept sort turns around at once. */
    private fun setDirection(item: MenuItem, descending: Boolean) {
        val group = host.currentGroup() ?: return
        if (BanShanSession.isManagedGroup(group.id)) {
            host.snackbar(R.string.banshan_protected_action).show()
            return
        }
        sortDescending = descending
        item.isChecked = true
        if (group.keepsSorted()) GroupActions.sort(host, group.id, group.sort.method, descending)
    }

    /** ProfilesTableModel::columnOfSort: the item of the kept sort, Manual order without one. */
    private fun sortItem(group: ProxyGroup): Int = when (group.sort.method) {
        GroupSortMethod.BY_TYPE, GroupSortMethod.BY_SECURITY ->
            if (TypeBy.of(group.typeSortBy) == TypeBy.BY_SECURITY) R.id.action_sort_security else R.id.action_sort_type

        GroupSortMethod.BY_ADDRESS -> R.id.action_sort_address
        GroupSortMethod.BY_NAME -> R.id.action_sort_name
        GroupSortMethod.BY_TEST_RESULT, GroupSortMethod.BY_LATENCY -> R.id.action_sort_test_result
        GroupSortMethod.BY_TRAFFIC -> R.id.action_sort_traffic
        GroupSortMethod.RAW, GroupSortMethod.BY_ID -> R.id.action_sort_manual
    }

    private fun toggleShown(menu: Menu, item: MenuItem) {
        item.isChecked = !item.isChecked
        val group = host.currentGroup() ?: return
        GroupActions.setShownItems(
            host,
            group.id,
            outIp = menu.findItem(R.id.action_show_out_ip)?.isChecked == true,
            speed = menu.findItem(R.id.action_show_speed)?.isChecked == true,
        )
    }

    /** Before the ⋮ menu opens: what applies to the current group and the running test / service. */
    fun prepare(menu: Menu) {
        val group = host.currentGroup()
        menu.findItem(R.id.action_update_subscription)?.isVisible = group?.isSubscription == true
        menu.findItem(R.id.action_test_stop)?.isVisible = host.testState.running
        // The login subscription's group is managed by its account: no sorting, cleanup or resolving.
        val managed = group != null && BanShanSession.isManagedGroup(group.id)
        menu.findItem(R.id.action_sort)?.isVisible = !managed
        menu.findItem(R.id.action_remove)?.isVisible = !managed
        menu.findItem(R.id.action_resolve_domains)?.isVisible = !managed
        if (group != null && group.keepsSorted()) sortDescending = group.sort.descending
        menu.findItem(if (sortDescending) R.id.action_sort_descending else R.id.action_sort_ascending)?.isChecked = true
        if (group != null) {
            menu.findItem(sortItem(group))?.isChecked = true
            menu.findItem(
                when (TestBy.of(group.testSortBy)) {
                    TestBy.LATENCY -> R.id.action_sort_test_latency
                    TestBy.DL_SPEED -> R.id.action_sort_test_download
                    TestBy.UL_SPEED -> R.id.action_sort_test_upload
                    TestBy.IP_OUT -> R.id.action_sort_test_ip_out
                }
            )?.isChecked = true
            menu.findItem(
                when (TrafficBy.of(group.trafficSortBy)) {
                    TrafficBy.TOTAL -> R.id.action_sort_traffic_total
                    TrafficBy.DL -> R.id.action_sort_traffic_downloaded
                    TrafficBy.UL -> R.id.action_sort_traffic_uploaded
                }
            )?.isChecked = true
            val shown = TestShowItems.of(group.testItemsToShow)
            menu.findItem(R.id.action_show_out_ip)?.isChecked = shown.showIp
            menu.findItem(R.id.action_show_speed)?.isChecked = shown.showSpeed
        }
    }

    fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_test_stop -> TestSessionClient.stop()
            R.id.action_layout -> host.setDoubleColumn(!host.doubleColumn)
            else -> return onGroupItemClick(item, host.currentGroup() ?: return false)
        }
        return true
    }

    private fun onGroupItemClick(item: MenuItem, group: ProxyGroup): Boolean {
        val id = group.id
        if (item.itemId in managedActions && BanShanSession.isManagedGroup(id)) {
            host.snackbar(R.string.banshan_protected_action).show()
            return true
        }
        when (item.itemId) {
            R.id.action_update_subscription -> GroupActions.updateSubscription(group)
            R.id.action_test_url -> ProfileTests.startGroup(host, TestSpec.KIND_URL, id)
            R.id.action_test_ip -> ProfileTests.startGroup(host, TestSpec.KIND_IP, id)
            R.id.action_test_speed -> ProfileTests.startGroup(host, TestSpec.KIND_SPEED, id)
            R.id.action_clear_test_results -> GroupActions.clearTestResults(host, group)
            R.id.action_remove_duplicates -> GroupActions.removeDuplicates(host, id)
            R.id.action_remove_unavailable -> GroupActions.removeUnavailable(host, id)
            R.id.action_remove_invalid -> GroupActions.removeInvalid(host, group)
            R.id.action_remove_insecure -> GroupActions.removeInsecure(host, id)
            R.id.action_resolve_domains -> GroupActions.resolveDomains(host, group)
            R.id.action_clear_traffic_statistics -> GroupActions.clearTraffic(host, group)

            // the header menu's "Stop Sorting": the current order becomes the manual one
            R.id.action_sort_manual -> GroupActions.stopSorting(host, id)

            // the Type column's "Sort By: Type / Security" stores type_sort_by
            R.id.action_sort_type -> sort(id, GroupSortMethod.BY_TYPE) { it.typeSortBy = TypeBy.BY_TYPE.value }
            R.id.action_sort_security -> sort(id, GroupSortMethod.BY_SECURITY) {
                it.typeSortBy = TypeBy.BY_SECURITY.value
            }

            R.id.action_sort_address -> sort(id, GroupSortMethod.BY_ADDRESS)
            R.id.action_sort_name -> sort(id, GroupSortMethod.BY_NAME)
            R.id.action_sort_test_latency -> sortByTest(id, TestBy.LATENCY)
            R.id.action_sort_test_download -> sortByTest(id, TestBy.DL_SPEED)
            R.id.action_sort_test_upload -> sortByTest(id, TestBy.UL_SPEED)
            R.id.action_sort_test_ip_out -> sortByTest(id, TestBy.IP_OUT)
            R.id.action_sort_traffic_total -> sortByTraffic(id, TrafficBy.TOTAL)
            R.id.action_sort_traffic_downloaded -> sortByTraffic(id, TrafficBy.DL)
            R.id.action_sort_traffic_uploaded -> sortByTraffic(id, TrafficBy.UL)
            else -> return false
        }
        return true
    }

    private fun sort(groupId: Long, method: GroupSortMethod, save: ((ProxyGroup) -> Unit)? = null) {
        GroupActions.sort(host, groupId, method, sortDescending, save)
    }

    private fun sortByTest(groupId: Long, by: TestBy) {
        sort(groupId, GroupSortMethod.BY_TEST_RESULT) { it.testSortBy = by.value }
    }

    private fun sortByTraffic(groupId: Long, by: TrafficBy) {
        sort(groupId, GroupSortMethod.BY_TRAFFIC) { it.trafficSortBy = by.value }
    }
}
