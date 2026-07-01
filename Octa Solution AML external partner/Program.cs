using AML.Util;
using Microsoft.OpenApi.Models;
using Serilog;
using Serilog.Events;

namespace AML
{
    public class Program
    {
        public static void Main(string[] args)
        {
           
            if (!Directory.Exists("Logs"))
            {
                Directory.CreateDirectory("Logs");
            }
           
            Log.Logger = new LoggerConfiguration()
                .MinimumLevel.Debug()
                .MinimumLevel.Override("Microsoft", LogEventLevel.Warning)
                .MinimumLevel.Override("System", LogEventLevel.Warning)
                .WriteTo.Console()
                .WriteTo.File(
                    path: "Logs/log-.txt",
                    rollingInterval: RollingInterval.Day,
                    outputTemplate: "{Timestamp:yyyy-MM-dd HH:mm:ss.fff zzz} [{Level:u3}] {Message:lj}{NewLine}{Exception}"
                )
                .CreateLogger();
            try
            {
                var builder = WebApplication.CreateBuilder(args);

              // service registration
                builder.Host.UseSerilog();
                builder.Services.AddControllers();
                builder.Services.AddSingleton<DatabaseService>();
                builder.Services.AddTransient<HttpLoggingHandler>();
                builder.Services.AddSingleton<AMLBiz>();
                builder.Services.AddScoped<LogFilter>();
                builder.Services.AddHttpClient("LoggedClient")
                    .AddHttpMessageHandler<HttpLoggingHandler>();

                builder.Services.AddHttpClient("LoggedClient")
                   .AddHttpMessageHandler<HttpLoggingHandler>();

                builder.Services.AddHttpClient<OktaHttpService>()
                    .AddHttpMessageHandler<HttpLoggingHandler>();
                builder.Services.AddEndpointsApiExplorer();
                builder.Services.AddSwaggerGen(options =>
                {                   
                    options.SwaggerDoc("v1", new OpenApiInfo
                    {
                        Title = "AML API",
                        Version = "v1",
                        Description = "An ASP.NET Core AML API with Swagger",
                        Contact = new OpenApiContact
                        {
                            Name = "GME",
                            Email = "payments@gmeremit.com"
                        }
                    });

                    
                });
                var app = builder.Build();
              
                if (app.Environment.IsDevelopment() || app.Environment.IsStaging())
                {
                    app.UseSwagger();
                    app.UseSwaggerUI(options =>
                    {
                        options.SwaggerEndpoint("/swagger/v1/swagger.json", "AML API v1");
                        options.RoutePrefix = string.Empty;
                    });
                }
              
                app.UseAuthorization();
                app.MapControllers();
              
                app.Run();
            }
            catch (Exception ex)
            {
                Log.Fatal(ex, "Application terminated unexpectedly");
            }
            finally
            {
                Log.CloseAndFlush();
            }
        }
    }
}