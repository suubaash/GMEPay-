using AML.Model;

namespace AML
{
    public class AMLBiz
    {
        private OktaHttpService _oktaHttpService;

        public AMLBiz( OktaHttpService oktaPayHttpService) {
            this._oktaHttpService = oktaPayHttpService;
        }
        public string CreateRequest(AmlLog datam) {
            return _oktaHttpService.LogAML(datam);
        }


        public HttpClient GetHttpClient()
        {
            return _oktaHttpService.GetHttpClient();
        }
    }
}