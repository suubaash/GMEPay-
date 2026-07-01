using Microsoft.Data.SqlClient;
using Newtonsoft.Json;

namespace AML.Util
{
    public class HttpLoggingHandler : DelegatingHandler
    {
        private readonly DatabaseService _databaseService;

        public HttpLoggingHandler(DatabaseService databaseService)
        {
            _databaseService = databaseService;
        }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            string requestHeaders = JsonConvert.SerializeObject(request.Headers.ToDictionary(h => h.Key, h => h.Value));
            string requestBody = request.Content != null ? await request.Content.ReadAsStringAsync() : "";

            // Send the request
            HttpResponseMessage response = await base.SendAsync(request, cancellationToken);

            string responseHeaders = JsonConvert.SerializeObject(response.Headers.ToDictionary(h => h.Key, h => h.Value));
            string responseBody = response.Content != null ? await response.Content.ReadAsStringAsync() : "";

            // Insert log into database
            string insertQuery = $@"
            INSERT INTO TBL_HTTP_LOGS (Url, Method, RequestHeaders, RequestBody, ResponseHeaders, ResponseBody, StatusCode, Timestamp, Bound)
            VALUES (@Url, @Method, @RequestHeaders, @RequestBody, @ResponseHeaders, @ResponseBody, @StatusCode, @Timestamp, 'OUT')";

            using (var connection = new SqlConnection(_databaseService.ConnectionString))
            {
                connection.Open();
                using (var command = new SqlCommand(insertQuery, connection))
                {
                    command.Parameters.AddWithValue("@Url", request.RequestUri?.ToString());
                    command.Parameters.AddWithValue("@Method", request.Method.ToString());
                    command.Parameters.AddWithValue("@RequestHeaders", requestHeaders);
                    command.Parameters.AddWithValue("@RequestBody", requestBody);
                    command.Parameters.AddWithValue("@ResponseHeaders", responseHeaders);
                    command.Parameters.AddWithValue("@ResponseBody", responseBody);
                    command.Parameters.AddWithValue("@StatusCode", (int)response.StatusCode);
                    command.Parameters.AddWithValue("@Timestamp", DateTime.Now);

                    command.ExecuteNonQuery();
                }
            }

            return response;
        }
    }

}
