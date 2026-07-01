namespace AML.Model
{
    public class AmlLog
    {
        public string Msg { get; set; }
        public string Flag { get; set; }
        public int StatusCode { get; set; }
        public string RequestBody { get; set; }
        public string ResponseBody { get; set; }
        public DateTime CreateDate { get; set; }
    }
}
