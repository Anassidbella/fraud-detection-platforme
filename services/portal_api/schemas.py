from pydantic import BaseModel
from typing import List, Dict, Any

class ApplicationCreate(BaseModel):
    client_name: str
    domain_name: str
    rules: List[Dict[str, Any]]
    username: str
    password: str

class ApplicationCredentials(BaseModel):
    application_id: str
    api_token: str

class SdkDomainConfig(BaseModel):
    domainName: str
    mlflowVectorizerModelName: str
    modelStage: str
    onnxFileSubPathWithinModelDir: str
    scorerApiEndpointPath: str

class SdkRuntimeConfig(BaseModel):
    mlflowTrackingUri: str
    kongGatewayBaseUrl: str
    domainConfigurations: Dict[str, SdkDomainConfig]
    rules: Dict[str, List[Dict[str, Any]]]

class Token(BaseModel):
    access_token: str
    token_type: str