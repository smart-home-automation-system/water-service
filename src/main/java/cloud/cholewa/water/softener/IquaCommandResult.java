package cloud.cholewa.water.softener;

//What the iQua cloud answered a command of the softener (HAS-400) - DeviceCommandOutputBody of its OpenAPI; either left
//out is null
public record IquaCommandResult(String status, String message) {
}
