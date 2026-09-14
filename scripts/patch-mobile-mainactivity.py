from pathlib import Path

path = Path("app/src/main/java/com/dezgre/mobile/MainActivity.java")
source = path.read_text(encoding="utf-8")
old = '''        if ("products".equals(tab)) loadProducts();
        else if ("profile".equals(tab)) loadProfile();
        else if ("home".equals(tab)) loadHome();
        else showUnavailableSection(tab);'''
new = '''        if ("products".equals(tab)) loadProducts();
        else if ("profile".equals(tab)) loadProfile();
        else if ("home".equals(tab)) loadHome();
        else if ("panel".equals(tab)) loadDashboard();
        else if ("orders".equals(tab)) loadOrders();
        else showUnavailableSection(tab);'''

old_count = source.count(old)
new_count = source.count(new)
if new_count == 1 and old_count == 0:
    print("Mobile routes already wired: panel -> dashboard/summary, orders -> orders")
elif old_count == 1 and new_count == 0:
    path.write_text(source.replace(old, new, 1), encoding="utf-8")
    print("Mobile routes patched: panel -> dashboard/summary, orders -> orders")
else:
    raise SystemExit(
        f"Unexpected mobile route state: old={old_count}, new={new_count}; refusing broad rewrite"
    )
