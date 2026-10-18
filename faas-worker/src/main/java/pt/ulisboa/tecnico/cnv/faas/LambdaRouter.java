package pt.ulisboa.tecnico.cnv.faas;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Map;

import pt.ulisboa.tecnico.cnv.dna.DnaHandler;
import pt.ulisboa.tecnico.cnv.fractals.FractalsHandler;
import pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler;

public class LambdaRouter implements RequestHandler<Map<String, String>, String> {

    @Override
    public String handleRequest(Map<String, String> event, Context context) {
        // Extract the operation name from the JSON payload
        String operation = event.getOrDefault("operation", "");

        //context.getLogger().log("AWS Lambda received operation: " + operation);

        // Route to the correct workload handler
        switch (operation.toLowerCase()) {
            case "dna":
                return new DnaHandler().handleRequest(event, context);
            case "fractals":
                return new FractalsHandler().handleRequest(event, context);
            case "grayscott":
                return new GrayScottHandler().handleRequest(event, context);
            default:
                context.getLogger().log("ERROR: Unknown operation " + operation);
                return "{ \"error\": \"Unknown operation: " + operation + "\" }";
        }
    }
}