import java.util.*;
import org.voltdb.*;

public class BatchRemoveINodes extends VoltProcedure {

  // public final SQLStmt sql1 =
  //     new SQLStmt(
  //         "WITH RECURSIVE cte AS ("
  //             + "	SELECT id, parent FROM inodes d WHERE id = ?"
  //             + " UNION ALL"
  //             + " SELECT d.id, d.parent FROM cte"
  //             + " JOIN inodes d ON cte.id = d.parent"
  //             + " )"
  //             + " SELECT id FROM cte;");
  // public final SQLStmt sql2 = new SQLStmt("DELETE FROM inodes WHERE id = ?;");

  // public long run(long[] ids) throws VoltAbortException {
  //   for (int i = 0; i < ids.length; ++i) {
  //     voltQueueSQL(sql1, ids[i]);
  //   }
  //   VoltTable[] results = voltExecuteSQL();

  //   if (results[0].getRowCount() < 1) {
  //     return -1;
  //   }

  //   for (int j = 0; j < results.length; ++j) {
  //     for (int i = 0; i < results[j].getRowCount(); ++i) {
  //       voltQueueSQL(sql2, results[j].fetchRow(i).getLong(0));
  //     }
  //   }
  //   voltExecuteSQL();
  //   return 1;
  // }

  public final SQLStmt sql0 = new SQLStmt("SELECT id FROM inodes WHERE id = ? and header != 0;");
  public final SQLStmt sql1 = new SQLStmt("SELECT id FROM inodes WHERE parent = ?");
  public final SQLStmt sql2 = new SQLStmt("DELETE FROM inodes WHERE id = ?;");

  public long run(final long[] ids) throws VoltAbortException {
    for (int i = 0; i < ids.length; ++i) {
      voltQueueSQL(sql0, ids[i]);
    }

    VoltTable[] results = voltExecuteSQL();
    if (results[0].getRowCount() == ids.length) {
      for (int i = 0; i < ids.length; ++i) {
        voltQueueSQL(sql2, ids[i]);
      }
    } else {
      List<Long> set = new ArrayList<>();
      for (int i = 0; i < ids.length; ++i) {
        set.add(ids[i]);
      }

      int i = 0;
      while (i < set.size()) {
        long cid = set.get(i);
        i++;
        voltQueueSQL(sql1, cid);
        VoltTable[] res = voltExecuteSQL();
        int count = res[0].getRowCount();
        if (count < 1) {
          continue;
        }
        for (int j = 0; j < count; ++j) {
          set.add(res[0].fetchRow(j).getLong(0));
        }
      }

      for (Long kid : set) {
        voltQueueSQL(sql2, kid);
      }
    }

    voltExecuteSQL();
    return 1;
  }
}
