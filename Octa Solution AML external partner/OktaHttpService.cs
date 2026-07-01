using Microsoft.Data.SqlClient;
using AML.Model;

namespace AML
{
    public class OktaHttpService
    {
        private readonly ILogger<OktaHttpService> _logger;
        private readonly DatabaseService _databaseService;
        private readonly HttpClient _httpClient;
        public OktaHttpService(HttpClient httpClient, DatabaseService databaseService, ILogger<OktaHttpService> logger)
        {            
           this._logger = logger;
           this._databaseService = databaseService;
           this._httpClient = httpClient;
        }

        public HttpClient GetHttpClient()
        {
            return this._httpClient;
        }

        public string LogAML(AmlLog request)
        {
           
            try
            {
                _logger.LogInformation("Data saving process info log from the OktaHttp Service.");
                string insertQuery = @"
                INSERT INTO TBL_AML_LOGS (Msg, Flag,StatusCode, RequestBody, ResponseBody, CreatedDate)
                VALUES (@Msg,@Flag, @StatusCode, @RequestBody, @ResponseBody, @CreatedDate);
                SELECT SCOPE_IDENTITY();";

                long logId = 0;
                using (var connection = new SqlConnection(_databaseService.ConnectionString))
                {
                    connection.Open();
                    using (var command = new SqlCommand(insertQuery, connection))
                    {
                        command.Parameters.AddWithValue("@Msg", request.Msg);
                        command.Parameters.AddWithValue("@Flag", request.Flag);
                        command.Parameters.AddWithValue("@StatusCode", request.StatusCode);
                        command.Parameters.AddWithValue("@RequestBody", request.RequestBody);
                        command.Parameters.AddWithValue("@ResponseBody", request.ResponseBody);
                        command.Parameters.AddWithValue("@CreatedDate", DateTime.Now);

                        logId = Convert.ToInt64(command.ExecuteScalar());
                    }
                }
                _logger.LogInformation("Data saved info log from the OktaHttp Service.");
                return logId.ToString();
            }
            catch(Exception ex) 
            {
                _logger.LogError("Exception Error log from the OktaHttp Service." + ex.Message.ToString());
                return null;
            }
        }

    }    
}
