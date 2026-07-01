using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using System.Text.Json;

namespace AML.Util
{
    public static class JsonMapper
    {
        public static string Map(string maprule, string source, bool reverse = false)
        {

            JObject sourceJObject = JObject.Parse(source);
            JObject targetJObject = new JObject();


            var keyMappings = JsonConvert.DeserializeObject<Dictionary<string, string>>(maprule);
            if (keyMappings == null || keyMappings.Count < 1)
            {
                return null;
            }
            var keyMapping = reverse
                ? keyMappings.ToDictionary(kvp => kvp.Value, kvp => kvp.Key)
                : keyMappings;

            if (reverse)
            {
                foreach (var mapping in keyMapping)
                {
                    string targetPath = mapping.Key;
                    string sourceKey = mapping.Value;

                    JToken value = GetValueByPath(sourceJObject, targetPath);
                    if (value != null)
                    {
                        targetJObject[sourceKey] = value;
                    }
                }

                return (targetJObject.ToString(Newtonsoft.Json.Formatting.Indented));
            }

            foreach (var mapping in keyMapping)
            {
                string sourceKey = mapping.Key;
                string targetPath = mapping.Value;

                JToken value = sourceJObject[sourceKey];
                SetValueByPath(targetJObject, targetPath, value);
            }

            return targetJObject.ToString(Newtonsoft.Json.Formatting.Indented);
        }

        static void SetValueByPath(JObject target, string path, JToken value)
        {
            string[] parts = path.Split('.');
            JObject current = target;

            for (int i = 0; i < parts.Length - 1; i++)
            {
                string part = parts[i];

                if (part.Contains("["))
                {
                    string arrayName = part.Split('[')[0];
                    int index = int.Parse(part.Split('[')[1].TrimEnd(']'));

                    if (current[arrayName] == null)
                    {
                        current[arrayName] = new JArray();
                    }

                    JArray array = (JArray)current[arrayName];

                    while (array.Count <= index)
                    {
                        array.Add(new JObject());
                    }

                    current = (JObject)array[index];
                }
                else
                {
                    if (current[part] == null)
                    {
                        current[part] = new JObject();
                    }

                    current = (JObject)current[part];
                }
            }

            string lastPart = parts[parts.Length - 1];
            current[lastPart] = value;
        }

        static JToken GetValueByPath(JObject source, string path)
        {
            string[] parts = path.Split('.');
            JToken current = source;

            foreach (string part in parts)
            {
                if (part.Contains("["))
                {
                    string arrayName = part.Split('[')[0];
                    int index = int.Parse(part.Split('[')[1].TrimEnd(']'));

                    if (current[arrayName] is JArray array && array.Count > index)
                    {
                        current = array[index];
                    }
                    else
                    {
                        return null;
                    }
                }
                else
                {
                    if (current[part] != null)
                    {
                        current = current[part];
                    }
                    else
                    {
                        return null;
                    }
                }
            }

            return current;
        }
    }
    public class howTouse
    {
        private string sourceJson = @"{
            'CUSTOMER_NO': '12345',
            'CUSTOMER_NAME': 'John Doe',
            'CUSTOMER_ENG_NM': 'USA'
        }";
        private string srcJson = @"{
                'Customer': {
                    'No': '12345',
                    'Name': 'bin laden',
                    'Name_en': 'bin laden'

                }
            }";
        private string maprule = @"{
            'CUSTOMER_NO': 'Customer.No',
            'CUSTOMER_NAME': 'Customer.Name',
            'CUSTOMER_ENG_NM': 'Customer.Name_en'
        }";

        public object use()
        {
            return JsonMapper.Map(maprule, srcJson, true);
        }

    }
}
