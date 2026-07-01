using System.Data;
using Microsoft.Data.SqlClient;


namespace AML
{
    public class DatabaseService
    {
        public string ConnectionString { get; }
        private readonly ILogger<DatabaseService> _logger;

        public DatabaseService(IConfiguration configuration, ILogger<DatabaseService> logger )
        {       
            _logger = logger;
            ConnectionString = configuration.GetConnectionString("DefaultConnection")??"";
        }

        public DataTable ExecuteQuery(string query)
        {
            using var connection = new SqlConnection(ConnectionString);
            connection.Open();
            using (var command = new SqlCommand(query, connection))
            {
                using (var reader = command.ExecuteReader())
                {
                    var dataTable = new DataTable();
                    dataTable.Load(reader);
                    return dataTable;
                }
            }
        }

        public int ExecuteNonQuery(string query)
        {
            using (var connection = new SqlConnection(ConnectionString))
            {
                connection.Open();
                using (var command = new SqlCommand(query, connection))
                {
                    return command.ExecuteNonQuery();
                }
            }
        }

        public object ExecuteScalar(string query)
        {
            using (var connection = new SqlConnection(ConnectionString))
            {
                connection.Open();
                using (var command = new SqlCommand(query, connection))
                {
                    return command.ExecuteScalar();
                }
            }
        }


        public DataTable Execute(string procedureName, SqlParameter[] parameters = null)
        {
            _logger.LogInformation(GetRaw(procedureName, parameters));
            using (var connection = new SqlConnection(ConnectionString))
            {
                connection.Open();
                using (var command = new SqlCommand(procedureName, connection))
                {
                    command.CommandType = CommandType.StoredProcedure;

                    // Add parameters if any
                    if (parameters != null)
                    {
                        command.Parameters.AddRange(parameters);
                    }

                    using (var reader = command.ExecuteReader())
                    {
                        var dataTable = new DataTable();
                        dataTable.Load(reader);
                        return dataTable;
                    }
                }
            }
        }


        public DataSet ExecuteMany(string procedureName, SqlParameter[] parameters = null)
        {
            using (var connection = new SqlConnection(ConnectionString))
            {
                using (var command = new SqlCommand(procedureName, connection))
                {
                    command.CommandType = CommandType.StoredProcedure;
                    if (parameters != null)
                    {
                        command.Parameters.AddRange(parameters);
                    }

                    using (var adapter = new SqlDataAdapter(command))
                    {
                        var dataSet = new DataSet();
                        adapter.Fill(dataSet);
                       return dataSet;
                    }
                }
            }
        }
        public async Task<DataTable> ExecuteStoredProcedureAsync(string procedureName, SqlParameter[] parameters = null)
        {
            using (var connection = new SqlConnection(ConnectionString))
            {
                await connection.OpenAsync();
                using (var command = new SqlCommand(procedureName, connection))
                {
                    command.CommandType = CommandType.StoredProcedure;

                    // Add parameters if any
                    if (parameters != null)
                    {
                        command.Parameters.AddRange(parameters);
                    }

                    using (var reader = await command.ExecuteReaderAsync())
                    {
                        var dataTable = new DataTable();
                        dataTable.Load(reader);
                        return dataTable;
                    }
                }
            }
        }


        private string GetRaw(string procedureName, SqlParameter[] parameters = null) { 
            List<string> list = new List<string>();
            foreach (var parameter in parameters)
            {
                    list.Add(string.Format("{0}='{1}'", parameter.ParameterName,parameter.Value));                
            }

            return procedureName +" "+ string.Join(",", list.ToArray());
        }
    }
}
