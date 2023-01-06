package org.apache.hadoop.hdfs.db;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseNDExtraInfo {
  static final Logger LOG = LoggerFactory.getLogger(DatabaseNDExtraInfo.class);

  public DatabaseNDExtraInfo() {}

  public static void setSecretManagerSummary(
      int currentId, int tokenSequenceNumber, int numKeys, int numTokens) {
    try {
      DatabaseConnection obj = Database.getInstance().getConnection();
      Connection conn = obj.getConnection();
      String sql = "";
      String env = System.getenv("DATABASE");
      if (env.equals("VOLT")) {
        sql =
            "UPSERT INTO hdfs(id, currentId, tokenSequenceNumber, numKeys, numTokens) VALUES(0, ?, ?, ?, ?);";
      } else if (env.equals("IGNITE")) {
        sql =
            "MERGE INTO hdfs(id, currentId, tokenSequenceNumber, numKeys, numTokens) VALUES(0, ?, ?, ?, ?);";
      } else {
        sql =
            "INSERT INTO hdfs(id, currentId, tokenSequenceNumber, numKeys, numTokens) VALUES(0, ?, ?, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET currentId = ?, tokenSequenceNumber = ?, numKeys = ?, numTokens = ?;";
      }
      PreparedStatement pst = conn.prepareStatement(sql);
      pst.setInt(1, currentId);
      pst.setInt(2, tokenSequenceNumber);
      pst.setInt(3, numKeys);
      pst.setInt(4, numTokens);
      if (!env.equals("VOLT")) {
        pst.setInt(5, currentId);
        pst.setInt(6, tokenSequenceNumber);
        pst.setInt(7, numKeys);
        pst.setInt(8, numTokens);
      }
      pst.executeUpdate();
      pst.close();
      Database.getInstance().retConnection(obj);
    } catch (SQLException ex) {
      System.err.println(ex.getMessage());
    }
    if (LOG.isInfoEnabled()) {
      LOG.info("setSecretManagerSummary [SET]");
    }
  }

  public static void setStringTableSummary(int numEntry, int maskBits) {
    try {
      DatabaseConnection obj = Database.getInstance().getConnection();
      Connection conn = obj.getConnection();
      String sql = "";
      String env = System.getenv("DATABASE");
      if (env.equals("VOLT")) {
        sql = "UPSERT INTO hdfs(id, numEntry, maskBits) VALUES(0, ?, ?);";
      } else if (env.equals("IGNITE")) {
        sql = "MERGE INTO hdfs(id, numEntry, maskBits) VALUES(0, ?, ?);";
      } else {
        sql =
            "INSERT INTO hdfs(id, numEntry, maskBits) VALUES(0, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET numEntry = ?, maskBits = ?;";
      }
      PreparedStatement pst = conn.prepareStatement(sql);
      pst.setInt(1, numEntry);
      pst.setInt(2, maskBits);
      if (!env.equals("VOLT")) {
        pst.setInt(3, numEntry);
        pst.setInt(4, maskBits);
      }
      pst.executeUpdate();
      pst.close();
      Database.getInstance().retConnection(obj);
    } catch (SQLException ex) {
      System.err.println(ex.getMessage());
    }
    if (LOG.isInfoEnabled()) {
      LOG.info("setStringTableSummary [SET]");
    }
  }

  public Pair<Integer, Integer> getStringTableSummary() {
    ImmutablePair<Integer, Integer> result = null;
    try {
      DatabaseConnection obj = Database.getInstance().getConnection();
      Connection conn = obj.getConnection();
      String sql = "SELECT numEntry, maskBits FROM hdfs;";
      Statement st = conn.createStatement();
      ResultSet rs = st.executeQuery(sql);
      while (rs.next()) {
        result = new ImmutablePair<>(rs.getInt(1), rs.getInt(2));
      }
      rs.close();
      st.close();
      Database.getInstance().retConnection(obj);
    } catch (SQLException ex) {
      System.err.println(ex.getMessage());
    }
    if (LOG.isInfoEnabled()) {
      LOG.info("getStringTableSummary [GET]");
    }
    return result;
  }

  public List<Pair<Integer, String>> getStringTable(int size) {
    List<Pair<Integer, String>> result = new ArrayList<>(size);
    try {
      DatabaseConnection obj = Database.getInstance().getConnection();
      Connection conn = obj.getConnection();
      String sql = "SELECT id, str FROM stringtable;";
      Statement st = conn.createStatement();
      ResultSet rs = st.executeQuery(sql);
      while (rs.next()) {
        result.add(new ImmutablePair<>(rs.getInt(1), rs.getString(2)));
      }
      rs.close();
      st.close();
      Database.getInstance().retConnection(obj);
    } catch (SQLException ex) {
      System.err.println(ex.getMessage());
    }
    if (LOG.isInfoEnabled()) {
      LOG.info("getStringTable [GET]: " + size);
    }
    return result;
  }

  public static void setStringTable(Integer[] ids, String[] strs) {
    if (ids == null || ids.length == 0 || strs == null || strs.length == 0) {
      return;
    }

    try {
      String env = System.getenv("DATABASE");
      if (env.equals("VOLT")) {
        // call a stored procedure
        DatabaseConnection obj = Database.getInstance().getConnection();
        Connection conn = obj.getConnection();
        CallableStatement proc = conn.prepareCall("{call SetStringTable(?, ?)}");

        proc.setArray(1, conn.createArrayOf("INT", ids));
        proc.setArray(2, conn.createArrayOf("VARCHAR", strs));

        ResultSet rs = proc.executeQuery();
        while (rs.next()) {
          if (LOG.isInfoEnabled()) {
            LOG.info("setStringTable Insertion Return: " + rs.getLong(1));
          }
        }
        rs.close();
        proc.close();
        Database.getInstance().retConnection(obj);
      } else if (env.equals("IGNITE")) {
        DatabaseConnection obj = Database.getInstance().getConnection();
        Connection conn = obj.getConnection();
        String sql = "MERGE INTO stringtable(id, str) VALUES (?, ?);";
        PreparedStatement pst = conn.prepareStatement(sql);

        for (int i = 0; i < ids.length; ++i) {
          pst.setLong(1, ids[i]);
          pst.setString(2, strs[i]);
          pst.addBatch();
        }
        pst.executeBatch();
        pst.close();

        Database.getInstance().retConnection(obj);
      } else {
        String sql = "";
        for (int i = 0; i < ids.length; ++i) {
          String idStr = "'" + String.valueOf(ids[i]) + "'";
          String str = "'" + strs[i] + "'";
          sql +=
              "INSERT INTO stringtable(id, str) "
                  + "VALUES ("
                  + idStr
                  + ","
                  + str
                  + ") "
                  + "ON CONFLICT(id) DO UPDATE SET str = "
                  + str
                  + ";";
        }
        DatabaseConnection obj = Database.getInstance().getConnection();
        Connection conn = obj.getConnection();
        Statement st = conn.createStatement();
        st.executeUpdate(sql);
        st.close();
        Database.getInstance().retConnection(obj);
      }
    } catch (SQLException ex) {
      System.err.println(ex.getMessage());
    }
  }

  public Pair<Integer, Integer> getSecretManagerSummary() {
    ImmutablePair<Integer, Integer> result = null;
    try {
      DatabaseConnection obj = Database.getInstance().getConnection();
      Connection conn = obj.getConnection();
      String sql = "SELECT currentId, tokenSequenceNumber FROM hdfs;";
      Statement st = conn.createStatement();
      ResultSet rs = st.executeQuery(sql);
      while (rs.next()) {
        result = new ImmutablePair<>(rs.getInt(1), rs.getInt(2));
      }
      rs.close();
      st.close();
      Database.getInstance().retConnection(obj);
    } catch (SQLException ex) {
      System.err.println(ex.getMessage());
    }
    if (LOG.isInfoEnabled()) {
      LOG.info("getSecretManagerSummary [GET]");
    }
    return result;
  }

  public static void getDelegationKeys(List<Integer> ids, List<Long> dates, List<String> keys) {
    try {
      DatabaseConnection obj = Database.getInstance().getConnection();
      Connection conn = obj.getConnection();
      String sql = "SELECT id, expiryDate, key FROM delegationkeys;";
      Statement st = conn.createStatement();
      ResultSet rs = st.executeQuery(sql);
      while (rs.next()) {
        ids.add(rs.getInt(1));
        dates.add(rs.getLong(2));
        keys.add(rs.getString(3));
      }
      rs.close();
      st.close();
      Database.getInstance().retConnection(obj);
    } catch (SQLException ex) {
      System.err.println(ex.getMessage());
    }
    if (LOG.isInfoEnabled()) {
      LOG.info("getDelegationKeys [GET]");
    }
  }

  public static void setDelegationKeys(Integer[] ids, Long[] dates, String[] keys) {
    if (ids == null
        || ids.length == 0
        || dates == null
        || dates.length == 0
        || keys == null
        || keys.length == 0) {
      return;
    }

    try {
      String env = System.getenv("DATABASE");
      if (env.equals("VOLT")) {
        // call a stored procedure
        DatabaseConnection obj = Database.getInstance().getConnection();
        Connection conn = obj.getConnection();
        CallableStatement proc = conn.prepareCall("{call SetDelegationKeys(?, ?, ?)}");

        proc.setArray(1, conn.createArrayOf("INT", ids));
        proc.setArray(2, conn.createArrayOf("BIGINT", dates));
        proc.setArray(3, conn.createArrayOf("VARCHAR", keys));

        ResultSet rs = proc.executeQuery();
        while (rs.next()) {
          if (LOG.isInfoEnabled()) {
            LOG.info("setDelegationKeys Insertion Return: " + rs.getLong(1));
          }
        }
        rs.close();
        proc.close();
        Database.getInstance().retConnection(obj);
      } else if (env.equals("IGNITE")) {
        DatabaseConnection obj = Database.getInstance().getConnection();
        Connection conn = obj.getConnection();
        String sql = "MERGE INTO delegationkeys(id, expiryDate, key) VALUES (?, ?, ?);";
        PreparedStatement pst = conn.prepareStatement(sql);

        for (int i = 0; i < ids.length; ++i) {
          pst.setLong(1, ids[i]);
          pst.setLong(2, dates[i]);
          pst.setString(3, keys[i]);
          pst.addBatch();
        }
        pst.executeBatch();
        pst.close();

        Database.getInstance().retConnection(obj);      
      } else {
        String sql = "";
        for (int i = 0; i < ids.length; ++i) {
          String idStr = "'" + String.valueOf(ids[i]) + "'";
          String dateStr = "'" + String.valueOf(dates[i]) + "'";
          String keyStr = "'" + keys[i] + "'";
          sql +=
              "INSERT INTO delegationkeys(id, expiryDate, key) "
                  + "VALUES ("
                  + idStr
                  + ","
                  + dateStr
                  + ","
                  + keyStr
                  + ") "
                  + "ON CONFLICT(id) DO UPDATE SET expiryDate = "
                  + dateStr
                  + ", "
                  + "key = "
                  + keyStr
                  + ";";
        }

        DatabaseConnection obj = Database.getInstance().getConnection();
        Connection conn = obj.getConnection();
        Statement st = conn.createStatement();
        st.executeUpdate(sql);
        st.close();
        Database.getInstance().retConnection(obj);
      }
    } catch (SQLException ex) {
      System.err.println(ex.getMessage());
    }
  }

  public static void setPersistTokens(
      Integer[] seqnumbers,
      Integer[] masterkeys,
      Long[] issuedates,
      Long[] maxdates,
      Long[] expirydates,
      String[] owners,
      String[] renewers,
      String[] realusers) {
    if (owners == null || owners.length == 0) {
      return;
    }

    try {
      String env = System.getenv("DATABASE");
      if (env.equals("VOLT")) {
        // call a stored procedure
        DatabaseConnection obj = Database.getInstance().getConnection();
        Connection conn = obj.getConnection();
        ;
        CallableStatement proc =
            conn.prepareCall("{call SetPersistTokens(?, ?, ?, ?, ?, ?, ?, ?)}");

        proc.setArray(1, conn.createArrayOf("INT", seqnumbers));
        proc.setArray(2, conn.createArrayOf("INT", masterkeys));
        proc.setArray(3, conn.createArrayOf("BIGINT", issuedates));
        proc.setArray(4, conn.createArrayOf("BIGINT", maxdates));
        proc.setArray(5, conn.createArrayOf("BIGINT", expirydates));
        proc.setArray(6, conn.createArrayOf("VARCHAR", owners));
        proc.setArray(7, conn.createArrayOf("VARCHAR", renewers));
        proc.setArray(8, conn.createArrayOf("VARCHAR", realusers));

        ResultSet rs = proc.executeQuery();
        while (rs.next()) {
          if (LOG.isInfoEnabled()) {
            LOG.info("setPersistTokens Insertion Return: " + rs.getLong(1));
          }
        }
        rs.close();
        proc.close();
        Database.getInstance().retConnection(obj);
      } else {
        String sql =
            "DELETE FROM persisttokens;"
                + "INSERT INTO persisttokens(owner, renewer, realuser, issueDate, "
                + "maxDate, expiryDate, sequenceNumber, masterKeyId) VALUES ";
        for (int i = 0; i < owners.length; ++i) {
          sql +=
              "("
                  + "'"
                  + owners[i]
                  + "'"
                  + ","
                  + "'"
                  + renewers[i]
                  + "'"
                  + ","
                  + "'"
                  + realusers[i]
                  + "'"
                  + ","
                  + "'"
                  + String.valueOf(issuedates[i])
                  + "'"
                  + ","
                  + "'"
                  + String.valueOf(maxdates[i])
                  + "'"
                  + ","
                  + "'"
                  + String.valueOf(expirydates[i])
                  + "'"
                  + ","
                  + "'"
                  + String.valueOf(seqnumbers[i])
                  + "'"
                  + ","
                  + "'"
                  + String.valueOf(masterkeys[i])
                  + "'"
                  + "),";
        }
        sql = sql.substring(0, sql.length() - 1) + ";";

        DatabaseConnection obj = Database.getInstance().getConnection();
        Connection conn = obj.getConnection();
        Statement st = conn.createStatement();
        st.executeUpdate(sql);
        st.close();
        Database.getInstance().retConnection(obj);
      }
    } catch (SQLException ex) {
      System.err.println(ex.getMessage());
    }
  }

  public static void getPersistTokens(
      List<String> owners,
      List<String> renewers,
      List<String> realusers,
      List<Integer> seqnumbers,
      List<Integer> masterkeys,
      List<Long> issuedates,
      List<Long> expirydates,
      List<Long> maxdates) {
    try {
      DatabaseConnection obj = Database.getInstance().getConnection();
      Connection conn = obj.getConnection();
      String sql =
          "SELECT owner, renewer, realuser, issueDate, maxDate, "
              + "expiryDate, sequenceNumber, masterKeyId FROM persisttokens;";
      Statement st = conn.createStatement();
      ResultSet rs = st.executeQuery(sql);
      while (rs.next()) {
        owners.add(rs.getString(1));
        renewers.add(rs.getString(2));
        realusers.add(rs.getString(3));
        issuedates.add(rs.getLong(4));
        maxdates.add(rs.getLong(5));
        expirydates.add(rs.getLong(6));
        seqnumbers.add(rs.getInt(7));
        masterkeys.add(rs.getInt(8));
      }
      rs.close();
      st.close();
      Database.getInstance().retConnection(obj);
    } catch (SQLException ex) {
      System.err.println(ex.getMessage());
    }
    if (LOG.isInfoEnabled()) {
      LOG.info("getPersistTokens [GET]");
    }
  }
}
