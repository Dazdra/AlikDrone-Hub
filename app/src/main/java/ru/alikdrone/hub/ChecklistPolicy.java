package ru.alikdrone.hub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local preflight checklist presentation; never a flight-safety decision engine. */
public final class ChecklistPolicy {
    private static final List<Item> ITEMS = Collections.unmodifiableList(Arrays.asList(
            new Item(
                    "controller_power",
                    "Пульт заряжен",
                    "Это заряд пульта, а не состояние полётной батареи."
            ),
            new Item(
                    "flight_batteries",
                    "Полётные батареи проверены",
                    "Проверьте заряд, корпус и температуру каждой батареи вручную."
            ),
            new Item("propellers", "Пропеллеры и крепления осмотрены", ""),
            new Item("storage", "На карте памяти достаточно места", ""),
            new Item("weather_airspace", "Погода и ограничения воздушного пространства проверены", ""),
            new Item("home_gnss", "Home Point и GNSS подтверждены в DJI Fly", ""),
            new Item("notifications", "Лишние уведомления и фоновые приложения отключены", "")
    ));

    private ChecklistPolicy() {
    }

    public static List<Item> items() {
        return ITEMS;
    }

    public static Status evaluate(Set<String> checkedIds) {
        Set<String> normalized = checkedIds == null
                ? Collections.<String>emptySet()
                : new HashSet<>(checkedIds);
        List<String> missing = new ArrayList<>();
        int completed = 0;
        for (Item item : ITEMS) {
            if (normalized.contains(item.id)) {
                completed++;
            } else {
                missing.add(item.label);
            }
        }
        return new Status(completed, ITEMS.size(), missing);
    }

    public static String disclaimer() {
        return "Отметка чек-листа фиксирует только действия пользователя. "
                + "Она не подтверждает исправность дрона, право на полёт или безопасность полёта.";
    }

    public static final class Item {
        public final String id;
        public final String label;
        public final String hint;

        Item(String id, String label, String hint) {
            this.id = id;
            this.label = label;
            this.hint = hint;
        }
    }

    public static final class Status {
        public final int completed;
        public final int total;
        public final List<String> missing;

        Status(int completed, int total, List<String> missing) {
            this.completed = completed;
            this.total = total;
            this.missing = Collections.unmodifiableList(new ArrayList<>(missing));
        }

        public boolean allMarked() {
            return completed == total;
        }

        public String summary() {
            return "Отмечено " + completed + " из " + total
                    + ". Даже полный список не является допуском к полёту.";
        }
    }
}
