package dev.boarbot.entities.boaruser.queries;

import dev.boarbot.api.util.Configured;
import dev.boarbot.entities.boaruser.BoarUser;
import dev.boarbot.util.time.TimeUtil;

import java.sql.*;

public class BaseQueries implements Configured {
    private final BoarUser boarUser;

    public BaseQueries(BoarUser boarUser) {
        this.boarUser = boarUser;
    }

    void addUser(Connection connection) throws SQLException {
        if (this.userExists(connection)) {
            return;
        }

        String query = """
            INSERT INTO users (user_id, username) VALUES (?, ?)
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, this.boarUser.getUserID());
            statement.setString(2, this.boarUser.getUser().getName());
            statement.execute();
        }
    }

    public boolean userExists(Connection connection) throws SQLException {
        String query = """
            SELECT user_id
            FROM users
            WHERE user_id = ?;
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, this.boarUser.getUserID());

            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    public void updateUser(Connection connection) throws SQLException {
        this.updateUser(connection, false);
    }

    public void updateUser(Connection connection, boolean syncBypass) throws SQLException {
        if (!syncBypass) {
            this.boarUser.forceSynchronized();
        }

        String query = """
            SELECT last_daily_timestamp, last_streak_fix, first_joined_timestamp, boar_streak
            FROM users
            WHERE user_id = ?;
        """;

        long lastDailyLong = 0;
        long lastStreakLong = 0;
        long firstJoinedLong = 0;
        int boarStreak = 0;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, this.boarUser.getUserID());

            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    Timestamp lastDailyTimestamp = results.getTimestamp("last_daily_timestamp");
                    Timestamp lastStreakFixTimestamp = results.getTimestamp("last_streak_fix");
                    Timestamp firstJoinedTimestamp = results.getTimestamp("first_joined_timestamp");

                    if (lastDailyTimestamp != null) {
                        lastDailyLong = lastDailyTimestamp.getTime();
                    }

                    if (lastStreakFixTimestamp != null) {
                        lastStreakLong = lastStreakFixTimestamp.getTime();
                    }

                    if (firstJoinedTimestamp != null) {
                        firstJoinedLong = firstJoinedTimestamp.getTime();
                    }

                    boarStreak = results.getInt("boar_streak");
                }
            }
        }

        int newBoarStreak = boarStreak;
        long timeToReach = Math.max(Math.max(lastDailyLong, lastStreakLong), firstJoinedLong);
        long curTimeCheck = TimeUtil.getLastDailyResetMilli() - TimeUtil.getOneDayMilli();
        int curRemove = 7;
        int curDailiesMissed = 0;

        while (timeToReach < curTimeCheck) {
            newBoarStreak = Math.max(newBoarStreak - curRemove, 0);
            curTimeCheck -= TimeUtil.getOneDayMilli();
            curRemove *= 2;
            curDailiesMissed++;
        }

        if (curDailiesMissed > 0) {
            query = """
                UPDATE users
                SET boar_streak = ?, num_dailies_missed = num_dailies_missed + ?, last_streak_fix = ?
                WHERE user_id = ?
            """;

            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setInt(1, newBoarStreak);
                statement.setInt(2, curDailiesMissed);
                statement.setTimestamp(3, new Timestamp(TimeUtil.getLastDailyResetMilli()-1));
                statement.setString(4, this.boarUser.getUserID());
                statement.executeUpdate();
            }
        }
    }

    public long getLastChanged(Connection connection) throws SQLException {
        long lastChangedTimestamp = TimeUtil.getCurMilli();
        String query = """
            SELECT last_changed_timestamp
            FROM users
            WHERE user_id = ?;
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, this.boarUser.getUserID());

            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    lastChangedTimestamp = results.getTimestamp("last_changed_timestamp").getTime();
                }
            }
        }

        return lastChangedTimestamp;
    }

    public void giveBucks(Connection connection, long amount) throws SQLException {
        this.addUser(connection);
        this.boarUser.forceSynchronized();

        String query = """
            UPDATE users
            SET total_bucks = total_bucks + ?
            WHERE user_id = ?
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, amount);
            statement.setString(2, this.boarUser.getUserID());
            statement.executeUpdate();
        }
    }

    public void setNotifications(Connection connection, String channelID) throws SQLException {
        this.addUser(connection);
        this.boarUser.forceSynchronized();

        String query = """
            UPDATE users
            SET notifications_on = ?, notification_channel = ?
            WHERE user_id = ?
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setBoolean(1, channelID != null);
            statement.setString(2, channelID);
            statement.setString(3, this.boarUser.getUserID());
            statement.executeUpdate();
        }
    }

    public boolean getNotificationStatus(Connection connection) throws SQLException {
        String query = """
            SELECT notifications_on
            FROM users
            WHERE user_id = ?;
        """;

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, this.boarUser.getUserID());

            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    return results.getBoolean("notifications_on");
                }
            }
        }

        return false;
    }

    public long getBlessings(Connection connection) throws SQLException {
        return this.getBlessings(connection, 0);
    }

    public long getBlessings(Connection connection, int extraActive) throws SQLException {
        long blessings = 0;

        String blessingsQuery = """
            SELECT blessings, miracles_active
            FROM users
            WHERE user_id = ?;
        """;

        try (PreparedStatement blessingsStatement = connection.prepareStatement(blessingsQuery)) {
            blessingsStatement.setString(1, this.boarUser.getUserID());

            try (ResultSet results = blessingsStatement.executeQuery()) {
                if (results.next()) {
                    int miraclesActive = results.getInt("miracles_active");
                    blessings = results.getLong("blessings");
                    int miracleIncreaseMax = NUMS.getMiracleIncreaseMax();

                    int activesLeft = miraclesActive+extraActive;
                    for (; activesLeft>0; activesLeft--) {
                        long amountToAdd = (long) Math.min(Math.ceil(blessings * 0.1), miracleIncreaseMax);

                        if (amountToAdd == NUMS.getMiracleIncreaseMax()) {
                            break;
                        }

                        blessings += amountToAdd;
                    }

                    blessings += (long) activesLeft * miracleIncreaseMax;
                }
            }
        }

        return blessings;
    }
}