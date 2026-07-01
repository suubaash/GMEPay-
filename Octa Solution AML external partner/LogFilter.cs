using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.Data.SqlClient;
using Newtonsoft.Json;
using System.Text;

namespace AML
{
    public class LogFilter : IAsyncActionFilter
    {
        private readonly ILogger<LogFilter> _logger;
        private readonly DatabaseService _databaseService;

        public LogFilter(ILogger<LogFilter> logger, DatabaseService databaseService)
        {
            _logger = logger;
            _databaseService = databaseService;
        }

        public async Task OnActionExecutionAsync(ActionExecutingContext context, ActionExecutionDelegate next)
        {
            try
            {
                var request = context.HttpContext.Request;
                request.EnableBuffering();

                using var reader = new StreamReader(request.Body, Encoding.UTF8, leaveOpen: true);
                var requestBody = await reader.ReadToEndAsync();
                request.Body.Position = 0;

                var requestHeaders = JsonConvert.SerializeObject(request.Headers.ToDictionary(h => h.Key, h => h.Value));
                _logger.LogInformation("Incoming Request: {Method} {Path} \nHeaders: {Headers} \nBody: {Body}",
                    request.Method, request.Path, requestHeaders, requestBody);

                var executedContext = await next();

                var response = executedContext.HttpContext.Response;
                var originalBodyStream = response.Body;
                var memoryStream = new MemoryStream();
                response.Body = memoryStream;

                await executedContext.Result.ExecuteResultAsync(executedContext);

                memoryStream.Seek(0, SeekOrigin.Begin);
                var responseBody = await new StreamReader(memoryStream).ReadToEndAsync();
                memoryStream.Seek(0, SeekOrigin.Begin);

                var responseHeaders = JsonConvert.SerializeObject(response.Headers.ToDictionary(h => h.Key, h => h.Value));
                _logger.LogInformation("Outgoing Response: {StatusCode} \nHeaders: {Headers} \nBody: {Body}",
                    response.StatusCode, responseHeaders, responseBody);

                string insertQuery = @"
            INSERT INTO TBL_HTTP_LOGS (Url, Method, RequestHeaders, RequestBody, ResponseHeaders, ResponseBody, StatusCode, Timestamp, Bound)
            VALUES (@Url, @Method, @RequestHeaders, @RequestBody, @ResponseHeaders, @ResponseBody, @StatusCode, @Timestamp, 'IN');
            SELECT SCOPE_IDENTITY();";

                long logId = 0;
                using (var connection = new SqlConnection(_databaseService.ConnectionString))
                {
                    connection.Open();
                    using (var command = new SqlCommand(insertQuery, connection))
                    {
                        command.Parameters.AddWithValue("@Url", $"{request.Scheme}://{request.Host}{request.Path}{request.QueryString}");
                        command.Parameters.AddWithValue("@Method", request.Method.ToString());
                        command.Parameters.AddWithValue("@RequestHeaders", requestHeaders);
                        command.Parameters.AddWithValue("@RequestBody", requestBody);
                        command.Parameters.AddWithValue("@ResponseHeaders", responseHeaders);
                        command.Parameters.AddWithValue("@ResponseBody", responseBody);
                        command.Parameters.AddWithValue("@StatusCode", response.StatusCode);
                        command.Parameters.AddWithValue("@Timestamp", DateTime.Now);

                        logId = Convert.ToInt64(command.ExecuteScalar());
                    }
                }

                // Log and store LogId in HttpContext
                _logger.LogInformation("Stored LogId in HttpContext: {LogId}", logId);
                context.HttpContext.Items["LogId"] = logId;

                memoryStream.Seek(0, SeekOrigin.Begin);
                await memoryStream.CopyToAsync(originalBodyStream);
                memoryStream.Close();
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "An error occurred while processing the request and response.");
            }
        }
    }


}
