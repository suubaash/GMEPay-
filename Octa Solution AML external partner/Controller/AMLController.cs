using AML.Model;
using AML.Util;
using Microsoft.AspNetCore.Mvc;
using Newtonsoft.Json;
using System.Net;

namespace AML.Controller
{
    [ApiController]
    [Route("api/[controller]")]
    public class AMLController : ControllerBase
    {       
        private readonly ILogger<AMLController> _logger;
        private readonly IConfiguration _configuration;
        private readonly AMLBiz _amlBiz;

        public AMLController( ILogger<AMLController> logger, IConfiguration configuration, AMLBiz amlBiz)
        {          
            _logger = logger;
            _configuration = configuration;
            _amlBiz = amlBiz; 
        }



        [HttpGet]
        [Route("ping")]
        [ServiceFilter(typeof(LogFilter))]
        public ActionResult Get()
        {
            return Ok("pong");
        }


        /// <summary>
        ///  Currently support tran only.
        /// </summary>
        /// <param name="type">tran or customer</param>
        /// <returns>IActionResult</returns>
        [HttpPost]
        [Route("screening/{type}")]
        [ServiceFilter(typeof(LogFilter))]
        public async Task<IActionResult> ScreenEntity(string type)
        {
            try
            {
                
                using (var reader = new StreamReader(Request.Body))
                {
                    _logger.LogInformation("AML API saved info log in AML Controller.");
                    var requestBody = await reader.ReadToEndAsync();

                    var requestData = JsonConvert.DeserializeObject<Dictionary<string, object>>(requestBody);
                    if (requestData == null)
                    {
                        return BadRequest(new { StatusCode = HttpStatusCode.ExpectationFailed, Msg = "Invalid JSON format." });
                    }


                    var defFileName = "OctaConfig:amlscreeningmap";

                    if (type.ToLower().Equals("payout")) 
                    {
                        defFileName = "OctaConfig:amlscreeningpayout";
                    }
                    else if (type.ToLower().Equals("collection"))
                    {
                        defFileName = "OctaConfig:amlscreeningcollection";
                    }



                    if (!System.IO.File.Exists(_configuration[defFileName]))
                    {
                        return BadRequest(new { StatusCode = HttpStatusCode.NotFound, Msg = "AML screening mapper path not found" });
                    }

                    // Read the JSON file
                    string maprule = System.IO.File.ReadAllText(_configuration[defFileName]);
                    if (!string.IsNullOrEmpty(maprule))
                    {
                        var datamapped = JsonMapper.Map(maprule.ToString(), requestBody.ToString(), true);
                        var desMapData = JsonConvert.DeserializeObject<Dictionary<string, string>>(datamapped);

                        var request = new HttpRequestMessage
                        {
                            Method = HttpMethod.Post,
                            RequestUri = new Uri(_configuration["OctaConfig:BaseUrl"].ToString()),
                            Content = new FormUrlEncodedContent(desMapData)
                        };

                        _logger.LogInformation("Triggering Octa API....");
                        using (var response = await _amlBiz.GetHttpClient().SendAsync(request))
                        {
                            var responseBody = await response.Content.ReadAsStringAsync();
                            var responseJson = JsonConvert.DeserializeObject<Dictionary<string, object>>(responseBody);

                            string code = responseJson["CODE"]?.ToString()?? "U";
                            string msg = responseJson["CODE_MSG"]?.ToString();
                            _logger.LogInformation("Successfully fetched data from Octa API....");
                            string flag = code switch
                            {
                                "200" => "R",
                                "400" => "Y",
                                "900" => "G",
                                _ => "U"
                            };
                            var obj = new AmlLog
                            {
                                Msg = msg,
                                Flag= flag,
                                StatusCode= Convert.ToInt32(code),
                                RequestBody= requestBody,
                                ResponseBody= responseBody

                            };

                            var logId = _amlBiz.CreateRequest(obj);
                            _logger.LogInformation("Data saved info log in AML Controller.");
                            return Ok(new
                            {
                                UniqueId =logId ?? "0",
                                Msg = msg,
                                StatusCode = code,
                                Flag = flag,
                                ResponseBody = responseBody.ToString()
                            });
                        }
                    }
                    else
                    {
                        _logger.LogWarning("Failed to parse JSON or external API request failed in AML Controller.");
                        return BadRequest(new { StatusCode = HttpStatusCode.ExpectationFailed, Msg = "Failed to parse JSON or external API request failed." });
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogError("Exception Error log from the AML Controller." + ex.Message.ToString());
                return BadRequest(new { StatusCode=HttpStatusCode.ExpectationFailed, Msg = ex.Message.ToString() });
            }
        }
    }
}
