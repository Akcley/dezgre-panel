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
count = source.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one mobile route block, found {count}")
path.write_text(source.replace(old, new), encoding="utf-8")
print("Mobile routes patched: panel -> dashboard/summary, orders -> orders")
