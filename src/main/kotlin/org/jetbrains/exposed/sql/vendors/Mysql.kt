package org.jetbrains.exposed.sql.vendors

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.*


internal object MysqlDataTypeProvider : DataTypeProvider() {
    override fun dateTimeType(): String = if (MysqlDialect.isFractionDateTimeSupported()) "DATETIME(6)" else "DATETIME"
}

internal object MysqlFunctionProvider : FunctionProvider() {

    private object CharColumnType : StringColumnType() {
        override fun sqlType(): String = "CHAR"
    }

    override fun cast(expr: Expression<*>, type: IColumnType, builder: QueryBuilder) = when (type) {
        is StringColumnType -> super.cast(expr, CharColumnType, builder)
        else -> super.cast(expr, type, builder)
    }

    override fun random(seed: Int?) = "RAND(${seed?.toString().orEmpty()})"

    override fun <T : String?> ExpressionWithColumnType<T>.match(pattern: String, mode: MatchMode?): Op<Boolean> = MATCH(this, pattern, mode ?: MysqlMatchMode.STRICT)

    private class MATCH(val expr: ExpressionWithColumnType<*>, val pattern: String, val mode: MatchMode): Op<Boolean>() {
        override fun toSQL(queryBuilder: QueryBuilder): String =
                "MATCH(${expr.toSQL(queryBuilder)}) AGAINST ('$pattern' ${mode.mode()})"
    }

    private enum class MysqlMatchMode(val operator: String): FunctionProvider.MatchMode {
        STRICT("IN BOOLEAN MODE"),
        NATURAL_LANGUAGE("IN NATURAL LANGUAGE MODE");

        override fun mode() = operator
    }
}

internal object MysqlDialect : VendorDialect("mysql", MysqlDataTypeProvider, MysqlFunctionProvider) {

    override fun tableColumns(vararg tables: Table): Map<Table, List<Pair<String, Boolean>>> {

        val statement = TransactionManager.current().connection.createStatement()

        try {
            val rs = statement.executeQuery(
                    "SELECT DISTINCT TABLE_NAME, COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '${getDatabase()}'")
            return rs.extractColumns(tables) {
                Triple(it.getString("TABLE_NAME")!!, it.getString("COLUMN_NAME")!!, it.getBoolean("IS_NULLABLE"))
            }
        } finally {
            statement.close()
        }
    }

    override @Synchronized fun columnConstraints(vararg tables: Table): Map<Pair<String, String>, List<ForeignKeyConstraint>> {

        val constraints = HashMap<Pair<String, String>, MutableList<ForeignKeyConstraint>>()

        val tableNames = tables.map { it.tableName }

        fun inTableList(): String {
            if (tables.isNotEmpty()) {
                return " AND ku.TABLE_NAME IN ${tableNames.joinToString("','", prefix = "('", postfix = "')")}"
            }
            return ""
        }

        val statement = TransactionManager.current().connection.createStatement()
        try {
            val rs = statement.executeQuery(
                    "SELECT\n" +
                            "  rc.CONSTRAINT_NAME,\n" +
                            "  ku.TABLE_NAME,\n" +
                            "  ku.COLUMN_NAME,\n" +
                            "  ku.REFERENCED_TABLE_NAME,\n" +
                            "  ku.REFERENCED_COLUMN_NAME,\n" +
                            "  rc.DELETE_RULE\n" +
                            "FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc\n" +
                            "  INNER JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE ku\n" +
                            "    ON ku.TABLE_SCHEMA = rc.CONSTRAINT_SCHEMA AND rc.CONSTRAINT_NAME = ku.CONSTRAINT_NAME\n" +
                            "WHERE ku.TABLE_SCHEMA = '${getDatabase()}' ${inTableList()}")

            while (rs.next()) {
                val refereeTableName = rs.getString("TABLE_NAME")!!
                if (refereeTableName !in tableNames) continue
                val refereeColumnName = rs.getString("COLUMN_NAME")!!
                val constraintName = rs.getString("CONSTRAINT_NAME")!!
                val refTableName = rs.getString("REFERENCED_TABLE_NAME")!!
                val refColumnName = rs.getString("REFERENCED_COLUMN_NAME")!!
                val constraintDeleteRule = ReferenceOption.valueOf(rs.getString("DELETE_RULE")!!.replace(" ", "_"))
                constraints.getOrPut(Pair(refereeTableName, refereeColumnName), { arrayListOf() }).add(ForeignKeyConstraint(constraintName, refereeTableName, refereeColumnName, refTableName, refColumnName, constraintDeleteRule))
            }
        } finally {
            statement.close()
        }

        return constraints
    }

    override @Synchronized fun existingIndices(vararg tables: Table): Map<Table, List<Index>> {

        val constraints = HashMap<Table, MutableList<Index>>()

        val tableNames = tables.associateBy { it.nameInDatabaseCase() }

        val transaction = TransactionManager.current()
        val statement = transaction.connection.createStatement()
        try {
            val rs = statement.executeQuery(
                    """SELECT DISTINCT ind.* from (
                        SELECT
                            TABLE_NAME, INDEX_NAME, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS `COLUMNS`, NON_UNIQUE
                            FROM INFORMATION_SCHEMA.STATISTICS s
                            WHERE table_schema = '${getDatabase()}' and INDEX_NAME <> 'PRIMARY'
                            GROUP BY 1, 2, 4) ind
                LEFT JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                    on kcu.TABLE_NAME = ind.TABLE_NAME
                        and kcu.COLUMN_NAME = ind.columns
                        and TABLE_SCHEMA = '${getDatabase()}'
                        and kcu.REFERENCED_TABLE_NAME is not NULL
                WHERE kcu.COLUMN_NAME is NULL OR ind.NON_UNIQUE is FALSE;
        """)

            while (rs.next()) {
                val tableName = rs.getString("TABLE_NAME")!!
                if (tableName in tableNames.keys) {
                    val indexName = rs.getString("INDEX_NAME")!!
                    val columnsInIndex = rs.getString("COLUMNS")!!.split(',').map { transaction.quoteIfNecessary(it) }
                    val isUnique = rs.getInt("NON_UNIQUE") == 0
                    constraints.getOrPut(tableNames[tableName]!!, { arrayListOf() }).add(Index(indexName, tableName, columnsInIndex, isUnique))
                }
            }
        } finally {
            statement.close()
        }

        return constraints
    }

    override fun replace(table: Table, data: List<Pair<Column<*>, Any?>>, transaction: Transaction): String {
        val builder = QueryBuilder(true)
        val columns = data.joinToString { transaction.identity(it.first) }
        val values = data.joinToString { builder.registerArgument(it.first.columnType, it.second) }
        return "REPLACE INTO ${transaction.identity(table)} ($columns) VALUES ($values)"
    }

    override val DEFAULT_VALUE_EXPRESSION: String = "() VALUES ()"

    override fun insert(ignore: Boolean, table: Table, columns: List<Column<*>>, expr: String, transaction: Transaction): String {
        val def = super.insert(false, table, columns, expr, transaction)
        return if (ignore) def.replaceFirst("INSERT", "INSERT IGNORE") else def
    }

    override fun delete(ignore: Boolean, table: Table, where: String?, transaction: Transaction): String {
        val def = super.delete(false, table, where, transaction)
        return if (ignore) def.replaceFirst("DELETE", "DELETE IGNORE") else def
    }

    override fun dropIndex(tableName: String, indexName: String): String =
            "ALTER TABLE $tableName DROP INDEX $indexName"

    fun isFractionDateTimeSupported() = TransactionManager.current().db.metadata.let { (it.databaseMajorVersion == 5 && it.databaseMinorVersion >= 6) ||it.databaseMajorVersion > 5 }
}
